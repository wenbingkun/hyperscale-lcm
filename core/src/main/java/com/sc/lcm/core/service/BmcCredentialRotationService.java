package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台 BMC 托管账号密码轮换服务。
 * 支持两种触发方式：
 * <ol>
 *   <li>REST 接口按需触发单台设备：{@code POST /api/discovery/{id}/rotate-credentials}</li>
 *   <li>定时调度批量轮换所有已 CLAIMED/MANAGED 且启用托管账号的设备</li>
 * </ol>
 */
@ApplicationScoped
@Slf4j
public class BmcCredentialRotationService {

    @Inject
    RedfishManagedAccountProvisioner provisioner;

    @ConfigProperty(name = "lcm.rotation.enabled", defaultValue = "false")
    boolean rotationEnabled;

    @ConfigProperty(name = "lcm.rotation.schedule", defaultValue = "0 2 * * *")
    String rotationSchedule;

    /**
     * 对单台设备执行密码轮换并将结果写回数据库。
     * 调用方需确保在事务上下文或 withSession 中调用此方法。
     */
    public Uni<RotationResult> rotateDevice(String deviceId) {
        return Panache.withSession(() ->
                DiscoveredDevice.<DiscoveredDevice>findById(deviceId)
                        .onItem().transformToUni(device -> {
                            if (device == null) {
                                return Uni.createFrom().item(RotationResult.failure(deviceId, "Device not found."));
                            }
                            if (device.getCredentialProfileId() == null || device.getCredentialProfileId().isBlank()) {
                                return Uni.createFrom().item(RotationResult.failure(deviceId,
                                        "Device has no matched credential profile."));
                            }
                            if (device.getClaimStatus() != ClaimStatus.CLAIMED
                                    && device.getClaimStatus() != ClaimStatus.MANAGED) {
                                return Uni.createFrom().item(RotationResult.failure(deviceId,
                                        "Device is not in a claimable state (current: " + device.getClaimStatus() + ")."));
                            }

                            return CredentialProfile.<CredentialProfile>findById(device.getCredentialProfileId())
                                    .onItem().transformToUni(profile -> {
                                        if (profile == null) {
                                            return Uni.createFrom().item(RotationResult.failure(deviceId,
                                                    "Credential profile not found: " + device.getCredentialProfileId()));
                                        }
                                        if (!profile.isManagedAccountEnabled()) {
                                            return Uni.createFrom().item(RotationResult.skipped(deviceId,
                                                    "Managed account is disabled for credential profile '" + profile.getName() + "'."));
                                        }

                                        return provisioner.provision(device, profile)
                                                .onItem().transformToUni(provisionResult ->
                                                        Panache.withTransaction(() ->
                                                                DiscoveredDevice.<DiscoveredDevice>findById(deviceId)
                                                                        .map(attached -> {
                                                                            if (attached == null) {
                                                                                return RotationResult.failure(deviceId, "Device disappeared during rotation.");
                                                                            }
                                                                            attached.setLastRotationAt(LocalDateTime.now());
                                                                            attached.setClaimMessage(provisionResult.message());
                                                                            if (provisionResult.success()) {
                                                                                log.info("🔐 BMC credential rotated for {} ({})",
                                                                                        attached.getIpAddress(), attached.getCredentialProfileName());
                                                                                return RotationResult.success(deviceId, provisionResult.message());
                                                                            }
                                                                            log.warn("⚠️ BMC credential rotation failed for {}: {}",
                                                                                    attached.getIpAddress(), provisionResult.message());
                                                                            return RotationResult.failure(deviceId, provisionResult.message());
                                                                        })));
                                    });
                        }));
    }

    /**
     * 批量轮换所有已纳管且启用托管账号的 BMC。
     * 由定时调度触发，默认不启用（{@code lcm.rotation.enabled=false}）。
     */
    @Scheduled(cron = "{lcm.rotation.schedule}")
    public void scheduledRotation() {
        if (!rotationEnabled) {
            return;
        }

        log.info("⏰ Starting scheduled BMC credential rotation...");

        DiscoveredDevice.<DiscoveredDevice>find(
                        "claimStatus IN (?1, ?2)",
                        ClaimStatus.CLAIMED, ClaimStatus.MANAGED)
                .list()
                .onItem().transformToUni(devices -> {
                    if (devices.isEmpty()) {
                        log.info("No CLAIMED/MANAGED devices found for rotation.");
                        return Uni.createFrom().voidItem();
                    }
                    log.info("Rotating credentials for {} device(s)...", devices.size());

                    return Uni.join().all(
                            devices.stream()
                                    .map(device -> rotateDevice(device.getId())
                                            .onFailure().recoverWithItem(error -> {
                                                log.warn("Rotation failed for device {}: {}",
                                                        device.getId(), error.getMessage());
                                                return RotationResult.failure(device.getId(), error.getMessage());
                                            }))
                                    .toList()
                    ).andCollectFailures()
                            .replaceWithVoid();
                })
                .subscribe().with(
                        v -> log.info("✅ Scheduled BMC credential rotation complete."),
                        err -> log.error("❌ Scheduled BMC credential rotation failed", err));
    }

    public record RotationResult(String deviceId, Status status, String message) {

        public boolean success() {
            return status == Status.SUCCESS;
        }

        public enum Status { SUCCESS, SKIPPED, FAILURE }

        static RotationResult success(String deviceId, String message) {
            return new RotationResult(deviceId, Status.SUCCESS, message);
        }

        static RotationResult skipped(String deviceId, String message) {
            return new RotationResult(deviceId, Status.SKIPPED, message);
        }

        static RotationResult failure(String deviceId, String message) {
            return new RotationResult(deviceId, Status.FAILURE, message);
        }
    }
}
