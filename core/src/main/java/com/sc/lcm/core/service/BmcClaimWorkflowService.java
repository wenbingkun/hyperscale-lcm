package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一的 BMC claim 工作流。
 * <p>
 * 兼容路径 {@code /api/discovery/{id}/claim} 与 Phase 7 新增的 {@code /api/bmc/devices/{id}/claim}
 * 必须复用本服务，确保业务逻辑只在一处实现。
 */
@ApplicationScoped
@Slf4j
public class BmcClaimWorkflowService {

    @Inject
    Vertx vertx;

    @Inject
    RedfishClaimExecutor redfishClaimExecutor;

    @Inject
    RedfishManagedAccountProvisioner redfishManagedAccountProvisioner;

    @Inject
    AuditService auditService;

    Clock clock = Clock.systemDefaultZone();

    public Uni<ClaimWorkflowOutcome> claim(String deviceId, String actor) {
        return Panache.withSession(() ->
                DiscoveredDevice.<DiscoveredDevice>findById(deviceId)
                        .onItem().transformToUni(device -> {
                            if (device == null) {
                                return Uni.createFrom().item(ClaimWorkflowOutcome.notFound(deviceId));
                            }
                            if (device.getCredentialProfileId() == null || device.getCredentialProfileId().isBlank()) {
                                return Uni.createFrom().item(ClaimWorkflowOutcome.badRequest(deviceId,
                                        "Device has no matched credential profile"));
                            }

                            return CredentialProfile.<CredentialProfile>findById(device.getCredentialProfileId())
                                    .onItem().transformToUni(profile -> {
                                        if (profile == null) {
                                            return Uni.createFrom().item(ClaimWorkflowOutcome.badRequest(deviceId,
                                                    "Credential profile not found"));
                                        }

                                        return redfishClaimExecutor.execute(device, profile)
                                                .onItem().transformToUni(claimResult -> {
                                                    if (!claimResult.success()) {
                                                        return Uni.createFrom().item(new RawWorkflowResult(claimResult, null));
                                                    }
                                                    return redfishManagedAccountProvisioner.provision(device, profile)
                                                            .map(provisionResult -> new RawWorkflowResult(claimResult, provisionResult));
                                                })
                                                .onItem().transformToUni(raw -> runOnEventLoop(() ->
                                                        Panache.withTransaction(() ->
                                                                DiscoveredDevice.<DiscoveredDevice>findById(deviceId)
                                                                        .map(attached -> applyResult(attached, raw, deviceId)))))
                                                .onItem().call(outcome -> writeAudit(outcome, actor, device.getTenantId()));
                                    });
                        }));
    }

    private ClaimWorkflowOutcome applyResult(DiscoveredDevice attached, RawWorkflowResult raw, String deviceId) {
        if (attached == null) {
            return ClaimWorkflowOutcome.notFound(deviceId);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        attached.setLastAuthAttemptAt(now);
        attached.setClaimMessage(composeMessage(raw));
        attached.setBmcCapabilities(raw.claimResult().bmcCapabilities());
        if (raw.claimResult().bmcCapabilities() != null) {
            attached.setLastCapabilityProbeAt(now);
        }

        if (raw.claimResult().success()) {
            attached.setAuthStatus(AuthStatus.AUTHENTICATED);
            attached.setClaimStatus(ClaimStatus.CLAIMED);
            attached.setLastSuccessfulAuthMode(raw.claimResult().authMode());
            attached.setLastAuthFailureCode(null);
            attached.setLastAuthFailureReason(null);
            if (isBlank(attached.getManufacturerHint()) && !isBlank(raw.claimResult().manufacturer())) {
                attached.setManufacturerHint(raw.claimResult().manufacturer());
            }
            if (isBlank(attached.getModelHint()) && !isBlank(raw.claimResult().model())) {
                attached.setModelHint(raw.claimResult().model());
            }
            if (!isBlank(raw.claimResult().recommendedTemplate())) {
                attached.setRecommendedRedfishTemplate(raw.claimResult().recommendedTemplate());
            }
            if (raw.provisionResult() != null && raw.provisionResult().enabled()) {
                if (raw.provisionResult().success()) {
                    log.info("🔐 Managed BMC account ready for {}", attached.getIpAddress());
                } else {
                    log.warn("⚠️ Managed BMC account provisioning incomplete for {}: {}",
                            attached.getIpAddress(), raw.provisionResult().message());
                }
            }
            log.info("✅ Redfish claim validated for {}", attached.getIpAddress());
        } else {
            attached.setAuthStatus(AuthStatus.AUTH_FAILED);
            attached.setClaimStatus(ClaimStatus.DISCOVERED);
            attached.setLastAuthFailureCode(raw.claimResult().authFailureCode());
            attached.setLastAuthFailureReason(raw.claimResult().authFailureReason());
            log.warn("❌ Redfish claim failed for {}: {}",
                    attached.getIpAddress(), raw.claimResult().message());
        }

        return ClaimWorkflowOutcome.completed(attached, raw.claimResult(), raw.provisionResult());
    }

    private Uni<Void> writeAudit(ClaimWorkflowOutcome outcome, String actor, String tenantIdHint) {
        if (outcome == null || outcome.status() != Status.OK || outcome.claimResult() == null) {
            return Uni.createFrom().voidItem();
        }
        String tenantId = outcome.device() != null ? outcome.device().getTenantId() : tenantIdHint;
        String result = outcome.claimResult().success() ? "SUCCESS" : "FAILURE";
        return auditService.logBmcClaim(
                outcome.deviceId(),
                actor == null ? "SYSTEM" : actor,
                tenantId,
                outcome.claimResult().authMode(),
                result,
                outcome.claimResult().message())
                .onFailure().recoverWithItem(error -> {
                    log.warn("Failed to write BMC claim audit log for {}: {}", outcome.deviceId(), error.getMessage());
                    return null;
                });
    }

    private <T> Uni<T> runOnEventLoop(Supplier<Uni<T>> supplier) {
        return Uni.createFrom().emitter(emitter ->
                vertx.runOnContext(() ->
                        supplier.get().subscribe().with(emitter::complete, emitter::fail)));
    }

    private static String composeMessage(RawWorkflowResult raw) {
        if (raw == null || raw.claimResult() == null) {
            return "Claim result is unavailable.";
        }
        if (raw.provisionResult() == null || !raw.provisionResult().enabled()) {
            return raw.claimResult().message();
        }
        return raw.claimResult().message() + " " + raw.provisionResult().message();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Status { OK, NOT_FOUND, BAD_REQUEST }

    public record ClaimWorkflowOutcome(
            String deviceId,
            Status status,
            String errorMessage,
            DiscoveredDevice device,
            RedfishClaimExecutor.ClaimExecutionResult claimResult,
            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult provisionResult) {

        public static ClaimWorkflowOutcome notFound(String deviceId) {
            return new ClaimWorkflowOutcome(deviceId, Status.NOT_FOUND, "Device not found", null, null, null);
        }

        public static ClaimWorkflowOutcome badRequest(String deviceId, String message) {
            return new ClaimWorkflowOutcome(deviceId, Status.BAD_REQUEST, message, null, null, null);
        }

        public static ClaimWorkflowOutcome completed(
                DiscoveredDevice device,
                RedfishClaimExecutor.ClaimExecutionResult claimResult,
                RedfishManagedAccountProvisioner.ManagedAccountProvisionResult provisionResult) {
            String id = device != null ? device.getId() : null;
            return new ClaimWorkflowOutcome(id, Status.OK, null, device, claimResult, provisionResult);
        }
    }

    private record RawWorkflowResult(
            RedfishClaimExecutor.ClaimExecutionResult claimResult,
            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult provisionResult) {
    }
}
