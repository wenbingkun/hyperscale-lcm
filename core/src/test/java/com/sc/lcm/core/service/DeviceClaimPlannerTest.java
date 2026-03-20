package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceClaimPlannerTest {

    @Test
    @DisplayName("BMC 设备应在有凭据档案时进入 READY_TO_CLAIM")
    void shouldApplyMatchedProfile() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.10");
        device.setInferredType("BMC_ENABLED");

        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-1");
        profile.setName("rack-a-openbmc");
        profile.setRedfishTemplate("openbmc-rack-a");
        profile.setAutoClaim(true);
        profile.setUsernameSecretRef("env://LCM_BMC_RACK_A_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_RACK_A_PASSWORD");

        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> switch (key) {
            case "LCM_BMC_RACK_A_USERNAME" -> "admin";
            case "LCM_BMC_RACK_A_PASSWORD" -> "secret";
            default -> null;
        };

        DeviceClaimPlanner.applyMatchedProfile(device, profile, resolver.resolve(profile).await().indefinitely());

        assertEquals(AuthStatus.PROFILE_MATCHED, device.getAuthStatus());
        assertEquals(ClaimStatus.READY_TO_CLAIM, device.getClaimStatus());
        assertEquals("profile-1", device.getCredentialProfileId());
        assertEquals("openbmc-rack-a", device.getRecommendedRedfishTemplate());
        assertEquals("ENV", device.getCredentialSource());
    }

    @Test
    @DisplayName("缺少凭据档案时应进入 AUTH_PENDING")
    void shouldMarkAuthPendingWhenNoProfileMatches() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.11");
        device.setInferredType("BMC_ENABLED");

        DeviceClaimPlanner.applyMissingProfile(device);

        assertEquals(AuthStatus.AUTH_PENDING, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
        assertNull(device.getCredentialProfileId());
    }

    @Test
    @DisplayName("未命中凭据档案时应保留推荐模板")
    void shouldKeepRecommendedTemplateWhenProfileMissing() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.14");
        device.setInferredType("BMC_ENABLED");

        DeviceClaimPlanner.applyMissingProfile(device, "dell-idrac");

        assertEquals(AuthStatus.AUTH_PENDING, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
        assertEquals("dell-idrac", device.getRecommendedRedfishTemplate());
        assertTrue(device.getClaimMessage().contains("Recommended Redfish template"));
    }

    @Test
    @DisplayName("命中凭据档案但 secret ref 不可解析时不应进入 READY_TO_CLAIM")
    void shouldKeepClaimPendingWhenSecretsAreUnavailable() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.12");
        device.setInferredType("BMC_ENABLED");

        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-2");
        profile.setName("rack-b-openbmc");
        profile.setAutoClaim(true);
        profile.setUsernameSecretRef("env://LCM_BMC_RACK_B_USERNAME");
        profile.setPasswordSecretRef("vault://bmc/rack-b#password");

        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> null;

        DeviceClaimPlanner.applyMatchedProfile(device, profile, resolver.resolve(profile).await().indefinitely());

        assertEquals(AuthStatus.AUTH_PENDING, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
        assertEquals("rack-b-openbmc", device.getCredentialProfileName());
        assertTrue(device.getClaimMessage().contains("not ready"));
    }

    @Test
    @DisplayName("命中凭据档案但关闭 auto-claim 时应保持待执行状态")
    void shouldRequireManualExecutionWhenAutoClaimDisabled() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.13");
        device.setInferredType("BMC_ENABLED");

        CredentialProfile profile = new CredentialProfile();
        profile.setId("profile-3");
        profile.setName("rack-c-openbmc");
        profile.setAutoClaim(false);
        profile.setUsernameSecretRef("env://LCM_BMC_RACK_C_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_RACK_C_PASSWORD");

        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> "available";

        DeviceClaimPlanner.applyMatchedProfile(device, profile, resolver.resolve(profile).await().indefinitely());

        assertEquals(AuthStatus.PROFILE_MATCHED, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
        assertTrue(device.getClaimMessage().contains("auto-claim is disabled"));
    }

    @Test
    @DisplayName("非 BMC 候选应清理旧的 claim 匹配结果")
    void shouldResetClaimFieldsForNonBmcDevice() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setCredentialProfileId("profile-1");
        device.setCredentialProfileName("rack-a");
        device.setRecommendedRedfishTemplate("openbmc");
        device.setClaimMessage("Matched credential profile 'rack-a' for automated claim.");

        DeviceClaimPlanner.applyNonBmcState(device);

        assertEquals(AuthStatus.PENDING, device.getAuthStatus());
        assertEquals(ClaimStatus.DISCOVERED, device.getClaimStatus());
        assertNull(device.getCredentialProfileId());
        assertNull(device.getRecommendedRedfishTemplate());
    }

    @Test
    @DisplayName("开放 623 或 443 端口时应视为 BMC 候选")
    void shouldIdentifyBmcCandidateByPorts() {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setOpenPorts("[22, 623]");

        assertTrue(DeviceClaimPlanner.isBmcCandidate(device));

        device.setOpenPorts("[22, 9000]");
        assertFalse(DeviceClaimPlanner.isBmcCandidate(device));
    }

    private static SecretRefResolver newResolver() {
        SecretRefResolver resolver = new SecretRefResolver();
        resolver.secretManagerClient = new VaultSecretClient();
        return resolver;
    }
}
