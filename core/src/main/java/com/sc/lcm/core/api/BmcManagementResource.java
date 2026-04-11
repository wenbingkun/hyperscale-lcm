package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.service.AuditService;
import com.sc.lcm.core.service.BmcClaimWorkflowService;
import com.sc.lcm.core.service.BmcClaimWorkflowService.ClaimWorkflowOutcome;
import com.sc.lcm.core.service.BmcCredentialRotationService;
import com.sc.lcm.core.service.BmcCredentialRotationService.RotationResult;
import com.sc.lcm.core.service.MetricsService;
import com.sc.lcm.core.service.RedfishPowerActionService;
import com.sc.lcm.core.service.RedfishPowerActionService.PowerActionOutcome;
import com.sc.lcm.core.service.RedfishPowerActionService.PowerActionRequest;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 7 BMC 管理面 REST。
 * <p>
 * 替代 {@code /api/discovery/{id}/claim} 与 {@code /api/discovery/{id}/rotate-credentials} 的薄入口，
 * 并新增 capability 探测与受控电源动作。旧入口仍保留转发，将在 Phase 8 移除。
 */
@Path("/api/bmc/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR" })
@Slf4j
public class BmcManagementResource {

    @Inject
    BmcClaimWorkflowService bmcClaimWorkflowService;

    @Inject
    BmcCredentialRotationService bmcCredentialRotationService;

    @Inject
    RedfishPowerActionService redfishPowerActionService;

    @Inject
    AuditService auditService;

    @Inject
    MetricsService metricsService;

    /**
     * 返回设备最后一次 capability 探测快照与认证诊断。
     * 不发起新的 BMC 请求，避免误认为是健康检查。
     */
    @GET
    @Path("/{id}/capabilities")
    public Uni<Response> getCapabilities(@PathParam("id") String id) {
        return Panache.withSession(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .map(device -> {
                    if (device == null) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(error("DEVICE_NOT_FOUND", "Device not found"))
                                .build();
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("deviceId", device.getId());
                    body.put("ipAddress", device.getIpAddress());
                    body.put("bmcAddress", device.getBmcAddress());
                    body.put("manufacturer", device.getManufacturerHint());
                    body.put("model", device.getModelHint());
                    body.put("recommendedRedfishTemplate", device.getRecommendedRedfishTemplate());
                    body.put("redfishAuthModeOverride", device.getRedfishAuthModeOverride());
                    body.put("lastSuccessfulAuthMode", device.getLastSuccessfulAuthMode());
                    body.put("lastAuthAttemptAt", device.getLastAuthAttemptAt());
                    body.put("lastAuthFailureCode", device.getLastAuthFailureCode());
                    body.put("lastAuthFailureReason", device.getLastAuthFailureReason());
                    body.put("lastCapabilityProbeAt", device.getLastCapabilityProbeAt());
                    body.put("capabilities", device.getBmcCapabilities());
                    return Response.ok(body).build();
                }));
    }

    /**
     * 触发一次真实的 Redfish claim 验证（统一入口）。
     */
    @POST
    @Path("/{id}/claim")
    @RolesAllowed("ADMIN")
    public Uni<Response> claim(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String actor = resolveActor(securityContext);
        return bmcClaimWorkflowService.claim(id, actor)
                .map(BmcManagementResource::toClaimResponse);
    }

    /**
     * 触发单台设备的 BMC 托管账号密码轮换（统一入口）。
     */
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
                .map(BmcManagementResource::toRotationResponse);
    }

    /**
     * 执行受控 BMC 电源动作。
     * <p>
     * 必须携带 {@code Idempotency-Key} header；多 ComputerSystem 时必须显式 systemId。
     */
    @POST
    @Path("/{id}/power-actions")
    @RolesAllowed("ADMIN")
    public Uni<Response> powerAction(
            @PathParam("id") String id,
            @QueryParam("dryRun") @DefaultValue("false") boolean dryRun,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            PowerActionRequest request,
            @Context SecurityContext securityContext) {
        String actor = resolveActor(securityContext);
        return redfishPowerActionService.execute(id, actor, idempotencyKey, request, dryRun)
                .onItem().call(outcome -> writePowerActionAudit(id, actor, outcome, dryRun))
                .map(outcome -> {
                    metricsService.recordBmcPowerAction(
                            outcome.action() == null ? (request != null ? request.action() : "unknown") : outcome.action(),
                            outcome.status().name());
                    return toPowerResponse(outcome);
                });
    }

    private Uni<Void> writePowerActionAudit(String deviceId, String actor, PowerActionOutcome outcome, boolean dryRun) {
        if (outcome == null) {
            return Uni.createFrom().voidItem();
        }
        return auditService.logBmcPowerAction(
                        deviceId,
                        actor,
                        null,
                        outcome.action(),
                        outcome.systemId(),
                        outcome.authMode(),
                        outcome.status().name(),
                        outcome.taskLocation(),
                        dryRun)
                .onFailure().recoverWithItem(error -> {
                    log.warn("Failed to write BMC power-action audit log for {}: {}", deviceId, error.getMessage());
                    return null;
                });
    }

    private static Response toClaimResponse(ClaimWorkflowOutcome outcome) {
        return switch (outcome.status()) {
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity(error("DEVICE_NOT_FOUND", "Device not found"))
                    .build();
            case BAD_REQUEST -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("BAD_REQUEST", outcome.errorMessage()))
                    .build();
            case OK -> Response.ok(outcome.device()).build();
        };
    }

    private static Response toRotationResponse(RotationResult result) {
        return switch (result.status()) {
            case SUCCESS -> Response.ok(result).build();
            case SKIPPED -> Response.status(Response.Status.NOT_MODIFIED).entity(result).build();
            case FAILURE -> Response.status(Response.Status.BAD_REQUEST).entity(result).build();
        };
    }

    private static Response toPowerResponse(PowerActionOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status().name());
        body.put("action", outcome.action());
        body.put("systemId", outcome.systemId());
        body.put("targetUri", outcome.targetUri());
        body.put("authMode", outcome.authMode());
        body.put("taskLocation", outcome.taskLocation());
        body.put("allowedValues", outcome.allowedValues());
        body.put("message", outcome.message());
        body.put("replayed", outcome.replayed());
        if (outcome.failureCode() != null) {
            body.put("failureCode", outcome.failureCode());
        }
        return switch (outcome.status()) {
            case COMPLETED, DRY_RUN -> Response.ok(body).build();
            case ACCEPTED -> {
                Response.ResponseBuilder builder = Response.status(Response.Status.ACCEPTED).entity(body);
                if (outcome.taskLocation() != null && !outcome.taskLocation().isBlank()) {
                    builder.header("Location", outcome.taskLocation());
                }
                yield builder.build();
            }
            case BAD_REQUEST -> Response.status(Response.Status.BAD_REQUEST).entity(body).build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).entity(body).build();
            case FAILURE -> Response.status(Response.Status.BAD_GATEWAY).entity(body).build();
        };
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    private static String resolveActor(SecurityContext securityContext) {
        if (securityContext != null && securityContext.getUserPrincipal() != null
                && securityContext.getUserPrincipal().getName() != null
                && !securityContext.getUserPrincipal().getName().isBlank()) {
            return securityContext.getUserPrincipal().getName();
        }
        return "SYSTEM";
    }
}
