package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.service.BmcClaimWorkflowService;
import com.sc.lcm.core.service.BmcClaimWorkflowService.ClaimWorkflowOutcome;
import com.sc.lcm.core.service.DeviceClaimPlanner;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.Context;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备发现 REST API
 * 
 * 管理待纳管设备池，支持审批和拒绝操作
 */
@Path("/api/discovery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR" })
@Slf4j
public class DiscoveryResource {

    @jakarta.inject.Inject
    DeviceClaimPlanner deviceClaimPlanner;

    @jakarta.inject.Inject
    BmcClaimWorkflowService bmcClaimWorkflowService;

    @jakarta.inject.Inject
    com.sc.lcm.core.service.BmcCredentialRotationService bmcCredentialRotationService;

    @jakarta.inject.Inject
    com.sc.lcm.core.service.AuditService auditService;

    /**
     * 获取所有发现的设备
     */
    @GET
    public Uni<List<DiscoveredDevice>> listDevices(
            @QueryParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit) {

        if (status != null) {
            return DiscoveredDevice.findByStatus(DiscoveryStatus.valueOf(status.toUpperCase()));
        }
        return DiscoveredDevice.findAll().page(0, limit).list();
    }

    /**
     * 获取待审批设备数量
     */
    @GET
    @Path("/pending/count")
    public Uni<CountResponse> getPendingCount() {
        return DiscoveredDevice.countPending()
                .map(count -> new CountResponse(count));
    }

    /**
     * 手动添加设备到发现池
     */
    @POST
    public Uni<Response> addDevice(AddDeviceRequest request) {
        return DiscoveredDevice.findByIp(request.ipAddress())
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.CONFLICT)
                                        .entity(new ErrorResponse("Device already exists: " + request.ipAddress()))
                                        .build());
                    }

                    DiscoveredDevice device = new DiscoveredDevice();
                    device.setIpAddress(request.ipAddress());
                    device.setHostname(request.hostname());
                    device.setMacAddress(request.macAddress());
                    device.setDiscoveryMethod(DiscoveredDevice.DiscoveryMethod.MANUAL);
                    device.setInferredType(request.deviceType());
                    device.setManufacturerHint(request.manufacturerHint());
                    device.setModelHint(request.modelHint());
                    if ("BMC_ENABLED".equalsIgnoreCase(request.deviceType())) {
                        device.setBmcAddress(request.ipAddress());
                    }
                    device.setNotes(request.notes());
                    device.setTenantId(request.tenantId());

                    log.info("📡 Adding device manually: {}", request.ipAddress());

                    return Panache.withTransaction(() -> deviceClaimPlanner.plan(device)
                            .onItem().transformToUni(planned -> planned.persist().replaceWith(planned)))
                            .map(d -> Response.status(Response.Status.CREATED)
                                    .entity(d)
                                    .build());
                });
    }

    /**
     * 批准设备纳管
     */
    @POST
    @Path("/{id}/approve")
    @RolesAllowed("ADMIN")
    public Uni<Response> approveDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .onItem().transformToUni(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Device not found"))
                                        .build());
                    }
                    if (device.getStatus() != DiscoveryStatus.PENDING) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Device not in pending status"))
                                        .build());
                    }

                    device.setStatus(DiscoveryStatus.APPROVED);
                    device.setLastProbedAt(LocalDateTime.now());
                    log.info("✅ Device approved: {}", device.getIpAddress());

                    return Uni.createFrom().item(Response.ok(device).build());
                }));
    }

    /**
     * 拒绝设备
     */
    @POST
    @Path("/{id}/reject")
    @RolesAllowed("ADMIN")
    public Uni<Response> rejectDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .onItem().transformToUni(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Device not found"))
                                        .build());
                    }

                    device.setStatus(DiscoveryStatus.REJECTED);
                    log.info("❌ Device rejected: {}", device.getIpAddress());

                    return Uni.createFrom().item(Response.ok(device).build());
                }));
    }

    /**
     * 重新计算某个设备的凭据匹配与 claim 计划。
     */
    @POST
    @Path("/{id}/claim-plan")
    @RolesAllowed("ADMIN")
    public Uni<Response> refreshClaimPlan(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .onItem().transformToUni(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Device not found"))
                                        .build());
                    }

                    return deviceClaimPlanner.plan(device)
                            .onItem().transform(planned -> Response.ok(planned).build());
                }));
    }

    /**
     * 执行一次真实的 Redfish claim 验证。
     *
     * @deprecated Phase 7 起统一入口为 {@code POST /api/bmc/devices/{id}/claim}；
     *     本路径仅做薄转发，将在 Phase 8 删除。
     */
    @Deprecated(forRemoval = true)
    @POST
    @Path("/{id}/claim")
    @RolesAllowed("ADMIN")
    @NonBlocking
    public Uni<Response> executeClaim(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String actor = resolveActor(securityContext);
        return bmcClaimWorkflowService.claim(id, actor)
                .map(DiscoveryResource::toClaimResponse);
    }

    private static Response toClaimResponse(ClaimWorkflowOutcome outcome) {
        return switch (outcome.status()) {
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Device not found"))
                    .build();
            case BAD_REQUEST -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(outcome.errorMessage()))
                    .build();
            case OK -> Response.ok(outcome.device()).build();
        };
    }

    /**
     * 手动触发单台设备的 BMC 托管账号密码轮换。
     *
     * @deprecated Phase 7 起统一入口为 {@code POST /api/bmc/devices/{id}/rotate-credentials}；
     *     本路径仅做薄转发，将在 Phase 8 删除。
     */
    @Deprecated(forRemoval = true)
    @POST
    @Path("/{id}/rotate-credentials")
    @RolesAllowed("ADMIN")
    public Uni<Response> rotateCredentials(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String actor = resolveActor(securityContext);
        return bmcCredentialRotationService.rotateDevice(id)
                .onItem().call(result -> auditService.logBmcRotateCredentials(
                                id,
                                actor,
                                null,
                                result.status().name(),
                                result.message())
                        .onFailure().recoverWithItem(error -> {
                            log.warn("Failed to write BMC rotate-credentials audit log for {}: {}",
                                    id, error.getMessage());
                            return null;
                        }))
                .map(result -> switch (result.status()) {
                    case SUCCESS -> Response.ok(result).build();
                    case SKIPPED -> Response.status(Response.Status.NOT_MODIFIED).entity(result).build();
                    case FAILURE -> Response.status(Response.Status.BAD_REQUEST).entity(result).build();
                });
    }

    private static String resolveActor(SecurityContext securityContext) {
        if (securityContext != null && securityContext.getUserPrincipal() != null
                && securityContext.getUserPrincipal().getName() != null
                && !securityContext.getUserPrincipal().getName().isBlank()) {
            return securityContext.getUserPrincipal().getName();
        }
        return "SYSTEM";
    }

    /**
     * 删除设备
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Uni<Response> deleteDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.deleteById(id)
                .map(deleted -> {
                    if (deleted) {
                        return Response.noContent().build();
                    }
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(new ErrorResponse("Device not found"))
                            .build();
                }));
    }

    // ============== DTOs ==============

    public record AddDeviceRequest(
            String ipAddress,
            String hostname,
            String macAddress,
            String deviceType,
            String manufacturerHint,
            String modelHint,
            String notes,
            String tenantId) {
    }

    public record CountResponse(long count) {
    }

    public record ErrorResponse(String error) {
    }
}
