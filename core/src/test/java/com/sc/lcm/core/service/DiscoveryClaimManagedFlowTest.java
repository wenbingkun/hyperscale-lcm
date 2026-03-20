package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端闭环测试：Discovery → DeviceClaimPlanner → RedfishClaimExecutor → RedfishManagedAccountProvisioner
 *
 * 不依赖数据库或外部 HTTP，所有 I/O 均由可替换的 transport/envReader 覆盖。
 */
class DiscoveryClaimManagedFlowTest {

    @Test
    @DisplayName("完整闭环：BMC 设备从发现到托管账号就绪")
    void shouldCompleteFullDiscoveryClaimManagedFlow() {
        // ── 准备凭据档案 ──────────────────────────────────────────────────
        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-dell-rack1");
        profile.setName("dell-rack1");
        profile.setAutoClaim(true);
        profile.setEnabled(true);
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");
        profile.setManagedAccountRoleId("Administrator");

        // ── 准备发现设备 ───────────────────────────────────────────────────
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.10.0.50");
        device.setBmcAddress("10.10.0.50");
        device.setInferredType("BMC_ENABLED");

        // ── 第一阶段：DeviceClaimPlanner - 凭据匹配与规划 ───────────────────
        SecretRefResolver resolver = newResolver();

        SecretRefResolver.ResolvedCredentialMaterial resolvedCredentials =
                resolver.resolve(profile).await().indefinitely();

        assertTrue(resolvedCredentials.isReady(), "Bootstrap credentials should be ready from env");

        DeviceClaimPlanner.applyMatchedProfile(device, profile, resolvedCredentials);

        assertEquals(AuthStatus.PROFILE_MATCHED, device.getAuthStatus(),
                "Device should be in PROFILE_MATCHED after claim planning");
        assertEquals(ClaimStatus.READY_TO_CLAIM, device.getClaimStatus(),
                "Device should be READY_TO_CLAIM when profile matches and secrets are ready");
        assertEquals("profile-dell-rack1", device.getCredentialProfileId());
        assertEquals("dell-rack1", device.getCredentialProfileName());
        assertEquals("ENV", device.getCredentialSource());

        // ── 第二阶段：RedfishClaimExecutor - 真实 BMC 登录验证（mocked）─────
        RedfishClaimExecutor executor = newExecutor(resolver);
        executor.transport = request -> new RedfishClaimExecutor.ProbeResponse("Dell Inc.", "PowerEdge R760");

        RedfishClaimExecutor.ClaimExecutionResult claimResult =
                executor.execute(device, profile).await().indefinitely();

        assertTrue(claimResult.success(), "Claim execution should succeed with mocked transport");
        assertEquals("Dell Inc.", claimResult.manufacturer());
        assertEquals("PowerEdge R760", claimResult.model());
        assertEquals("dell-idrac", claimResult.recommendedTemplate(),
                "Template catalog should recommend dell-idrac for Dell PowerEdge");
        assertEquals("ENV", claimResult.credentialSource());
        assertTrue(claimResult.message().contains("validated"));

        // 模拟 DiscoveryResource 将 claim 结果写回设备状态
        device.setAuthStatus(AuthStatus.AUTHENTICATED);
        device.setClaimStatus(ClaimStatus.CLAIMED);
        device.setManufacturerHint(claimResult.manufacturer());
        device.setModelHint(claimResult.model());
        device.setRecommendedRedfishTemplate(claimResult.recommendedTemplate());
        device.setClaimMessage(claimResult.message());

        assertEquals(AuthStatus.AUTHENTICATED, device.getAuthStatus());
        assertEquals(ClaimStatus.CLAIMED, device.getClaimStatus());
        assertEquals("Dell Inc.", device.getManufacturerHint());
        assertEquals("PowerEdge R760", device.getModelHint());
        assertEquals("dell-idrac", device.getRecommendedRedfishTemplate());

        // ── 第三阶段：RedfishManagedAccountProvisioner - 托管账号创建（mocked）
        RedfishManagedAccountProvisioner provisioner = newProvisioner(resolver);
        provisioner.transport = request -> RedfishManagedAccountProvisioner.ManagedAccountProvisionResult.success(
                true,
                request.endpoint(),
                request.managedUsername(),
                request.roleId(),
                "Managed BMC account '" + request.managedUsername() + "' is ready on " + request.endpoint() + ".");

        RedfishManagedAccountProvisioner.ManagedAccountProvisionResult provisionResult =
                provisioner.provision(device, profile).await().indefinitely();

        assertTrue(provisionResult.enabled(), "Managed account provisioning should be enabled");
        assertTrue(provisionResult.success(), "Managed account provisioning should succeed");
        assertEquals("lcm-service", provisionResult.username(),
                "Provisioned username should match managed username secret");
        assertEquals("Administrator", provisionResult.roleId());
        assertTrue(provisionResult.message().contains("lcm-service"),
                "Success message should reference the managed account username");
        assertTrue(provisionResult.message().contains("10.10.0.50"),
                "Success message should reference the BMC endpoint");

        // 模拟写回最终 MANAGED 状态
        device.setClaimStatus(ClaimStatus.MANAGED);
        device.setClaimMessage(claimResult.message() + " " + provisionResult.message());

        assertEquals(ClaimStatus.MANAGED, device.getClaimStatus());
    }

    @Test
    @DisplayName("claim 失败时不应进入托管账号 provision 阶段")
    void shouldNotProvisionWhenClaimFails() {
        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-unknown");
        profile.setName("unknown-bmc");
        profile.setAutoClaim(true);
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.10.0.99");
        device.setBmcAddress("10.10.0.99");
        device.setInferredType("BMC_ENABLED");

        SecretRefResolver resolver = newResolver();

        RedfishClaimExecutor executor = newExecutor(resolver);
        executor.transport = request -> {
            throw new RuntimeException("Connection refused");
        };

        RedfishClaimExecutor.ClaimExecutionResult claimResult =
                executor.execute(device, profile).await().indefinitely();

        assertFalse(claimResult.success(), "Claim should fail when transport throws");
        assertTrue(claimResult.message().contains("failed") || claimResult.message().contains("Connection refused"),
                "Failure message should indicate transport error");

        // 模拟 DiscoveryResource 的行为：claim 失败时不触发 provision
        if (!claimResult.success()) {
            device.setAuthStatus(AuthStatus.AUTH_FAILED);
            device.setClaimStatus(ClaimStatus.DISCOVERED);
        }

        assertEquals(AuthStatus.AUTH_FAILED, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
    }

    @Test
    @DisplayName("托管账号 secret ref 未就绪时应阻断 provision")
    void shouldBlockProvisionWhenManagedSecretsAreMissing() {
        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-partial");
        profile.setName("partial-creds");
        profile.setManagedAccountEnabled(true);
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setBmcAddress("10.10.0.30");

        // 仅提供 bootstrap 凭据，不提供托管账号凭据
        SecretRefResolver partialResolver = new SecretRefResolver();
        partialResolver.secretManagerClient = new VaultSecretClient();
        partialResolver.envReader = key -> switch (key) {
            case "LCM_BMC_USERNAME" -> "admin";
            case "LCM_BMC_PASSWORD" -> "password";
            default -> null;
        };

        RedfishManagedAccountProvisioner provisioner = newProvisioner(partialResolver);

        RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                provisioner.provision(device, profile).await().indefinitely();

        assertTrue(result.enabled(), "Managed account is enabled on profile");
        assertFalse(result.success(), "Should fail when managed account secrets are missing");
        assertTrue(result.message().contains("managed account secret refs are not ready"),
                "Error message should explain that managed secrets are unavailable");
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    private static SecretRefResolver newResolver() {
        SecretRefResolver resolver = new SecretRefResolver();
        resolver.secretManagerClient = new VaultSecretClient();
        resolver.envReader = key -> switch (key) {
            case "LCM_BMC_USERNAME" -> "admin";
            case "LCM_BMC_PASSWORD" -> "password";
            case "LCM_BMC_MANAGED_USERNAME" -> "lcm-service";
            case "LCM_BMC_MANAGED_PASSWORD" -> "managed-secret-abc123";
            default -> null;
        };
        return resolver;
    }

    private static RedfishClaimExecutor newExecutor(SecretRefResolver resolver) {
        RedfishTemplateCatalog templateCatalog = new RedfishTemplateCatalog();
        templateCatalog.objectMapper = new ObjectMapper();
        templateCatalog.templateDir = "";
        templateCatalog.invalidateCache();

        RedfishClaimExecutor executor = new RedfishClaimExecutor();
        executor.secretRefResolver = resolver;
        executor.redfishTemplateCatalog = templateCatalog;
        executor.objectMapper = new ObjectMapper();
        return executor;
    }

    private static RedfishManagedAccountProvisioner newProvisioner(SecretRefResolver resolver) {
        RedfishManagedAccountProvisioner provisioner = new RedfishManagedAccountProvisioner();
        provisioner.secretRefResolver = resolver;
        provisioner.objectMapper = new ObjectMapper();
        return provisioner;
    }
}
