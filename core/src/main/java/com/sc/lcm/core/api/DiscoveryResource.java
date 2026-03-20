package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.service.DeviceClaimPlanner;
import com.sc.lcm.core.service.RedfishClaimExecutor;
import com.sc.lcm.core.service.RedfishManagedAccountProvisioner;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
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
    RedfishClaimExecutor redfishClaimExecutor;

    @jakarta.inject.Inject
    RedfishManagedAccountProvisioner redfishManagedAccountProvisioner;

    @jakarta.inject.Inject
    com.sc.lcm.core.service.BmcCredentialRotationService bmcCredentialRotationService;

    /** 可在测试中替换以控制时间，默认使用系统时钟。 */
    Clock clock = Clock.systemDefaultZone();

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
     * 当前阶段先验证首次凭据是否可登录 BMC，并将结果回写到发现池状态机。
     */
    @POST
    @Path("/{id}/claim")
    @RolesAllowed("ADMIN")
    public Uni<Response> executeClaim(@PathParam("id") String id) {
        // 第一阶段：在 session 上下文中读取设备与凭据档案（不持有 DB 连接）
        return Panache.withSession(() ->
                DiscoveredDevice.<DiscoveredDevice>findById(id)
                        .onItem().transformToUni(device -> {
                            if (device == null) {
                                return Uni.createFrom().item(
                                        Response.status(Response.Status.NOT_FOUND)
                                                .entity(new ErrorResponse("Device not found"))
                                                .build());
                            }
                            if (device.getCredentialProfileId() == null || device.getCredentialProfileId().isBlank()) {
                                return Uni.createFrom().item(
                                        Response.status(Response.Status.BAD_REQUEST)
                                                .entity(new ErrorResponse("Device has no matched credential profile"))
                                                .build());
                            }

                            return CredentialProfile.<CredentialProfile>findById(device.getCredentialProfileId())
                                    .onItem().transformToUni(profile -> {
                                        if (profile == null) {
                                            return Uni.createFrom().item(
                                                    Response.status(Response.Status.BAD_REQUEST)
                                                            .entity(new ErrorResponse("Credential profile not found"))
                                                            .build());
                                        }

                                        // 第二阶段：session 外执行真实 Redfish HTTP 探测（阻塞 I/O 在 worker 线程）
                                        return redfishClaimExecutor.execute(device, profile)
                                                .onItem().transformToUni(result -> {
                                                    if (!result.success()) {
                                                        return Uni.createFrom().item(new ClaimWorkflowResult(result, null));
                                                    }
                                                    return redfishManagedAccountProvisioner.provision(device, profile)
                                                            .map(provisionResult -> new ClaimWorkflowResult(result, provisionResult));
                                                })
                                                // 第三阶段：将探测结果写回 DB（新事务，重新加载托管实体）
                                                .onItem().transformToUni(workflowResult -> Panache.withTransaction(() ->
                                                        DiscoveredDevice.<DiscoveredDevice>findById(id)
                                                                .map(attachedDevice -> {
                                                                    if (attachedDevice == null) {
                                                                        return Response.status(Response.Status.NOT_FOUND)
                                                                                .entity(new ErrorResponse("Device not found"))
                                                                                .build();
                                                                    }

                                                                    attachedDevice.setLastAuthAttemptAt(LocalDateTime.now(clock));
                                                                    attachedDevice.setClaimMessage(composeClaimMessage(workflowResult));

                                                                    if (workflowResult.claimResult().success()) {
                                                                        attachedDevice.setAuthStatus(AuthStatus.AUTHENTICATED);
                                                                        attachedDevice.setClaimStatus(ClaimStatus.CLAIMED);
                                                                        if ((attachedDevice.getManufacturerHint() == null || attachedDevice.getManufacturerHint().isBlank())
                                                                                && workflowResult.claimResult().manufacturer() != null
                                                                                && !workflowResult.claimResult().manufacturer().isBlank()) {
                                                                            attachedDevice.setManufacturerHint(workflowResult.claimResult().manufacturer());
                                                                        }
                                                                        if ((attachedDevice.getModelHint() == null || attachedDevice.getModelHint().isBlank())
                                                                                && workflowResult.claimResult().model() != null
                                                                                && !workflowResult.claimResult().model().isBlank()) {
                                                                            attachedDevice.setModelHint(workflowResult.claimResult().model());
                                                                        }
                                                                        if (workflowResult.claimResult().recommendedTemplate() != null
                                                                                && !workflowResult.claimResult().recommendedTemplate().isBlank()) {
                                                                            attachedDevice.setRecommendedRedfishTemplate(workflowResult.claimResult().recommendedTemplate());
                                                                        }
                                                                        if (workflowResult.provisionResult() != null && workflowResult.provisionResult().enabled()) {
                                                                            if (workflowResult.provisionResult().success()) {
                                                                                log.info("🔐 Managed BMC account ready for {}", attachedDevice.getIpAddress());
                                                                            } else {
                                                                                log.warn("⚠️ Managed BMC account provisioning incomplete for {}: {}",
                                                                                        attachedDevice.getIpAddress(),
                                                                                        workflowResult.provisionResult().message());
                                                                            }
                                                                        }
                                                                        log.info("✅ Redfish claim validated for {}", attachedDevice.getIpAddress());
                                                                    } else {
                                                                        attachedDevice.setAuthStatus(AuthStatus.AUTH_FAILED);
                                                                        attachedDevice.setClaimStatus(ClaimStatus.DISCOVERED);
                                                                        log.warn("❌ Redfish claim failed for {}: {}",
                                                                                attachedDevice.getIpAddress(),
                                                                                workflowResult.claimResult().message());
                                                                    }

                                                                    return Response.ok(attachedDevice).build();
                                                                })));
                                    });
                        }));
    }

    private static String composeClaimMessage(ClaimWorkflowResult workflowResult) {
        if (workflowResult == null || workflowResult.claimResult() == null) {
            return "Claim result is unavailable.";
        }
        if (workflowResult.provisionResult() == null || !workflowResult.provisionResult().enabled()) {
            return workflowResult.claimResult().message();
        }
        return workflowResult.claimResult().message() + " " + workflowResult.provisionResult().message();
    }

    /**
     * 手动触发单台设备的 BMC 托管账号密码轮换。
     * 要求设备处于 CLAIMED 或 MANAGED 状态，且凭据档案已启用 managedAccount。
     */
    @POST
    @Path("/{id}/rotate-credentials")
    @RolesAllowed("ADMIN")
    public Uni<Response> rotateCredentials(@PathParam("id") String id) {
        return bmcCredentialRotationService.rotateDevice(id)
                .map(result -> switch (result.status()) {
                    case SUCCESS -> Response.ok(result).build();
                    case SKIPPED -> Response.status(Response.Status.NOT_MODIFIED).entity(result).build();
                    case FAILURE -> Response.status(Response.Status.BAD_REQUEST).entity(result).build();
                });
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

    record ClaimWorkflowResult(
            RedfishClaimExecutor.ClaimExecutionResult claimResult,
            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult provisionResult) {
    }
}
