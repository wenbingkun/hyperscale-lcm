package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedfishManagedAccountProvisionerTest {

    @Test
    @DisplayName("启用托管账号时应在 claim 成功后创建标准 Redfish 账号")
    void shouldProvisionManagedAccount() {
        RedfishManagedAccountProvisioner provisioner = newProvisioner();
        provisioner.transport = request -> RedfishManagedAccountProvisioner.ManagedAccountProvisionResult.success(
                true,
                request.endpoint(),
                request.managedUsername(),
                request.roleId(),
                "Managed BMC account '" + request.managedUsername() + "' is ready.");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setBmcAddress("10.0.0.20");

        CredentialProfile profile = new CredentialProfile();
        profile.setName("rack-a-openbmc");
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");
        profile.setManagedAccountRoleId("Operator");

        RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                provisioner.provision(device, profile).await().indefinitely();

        assertTrue(result.enabled());
        assertTrue(result.success());
        assertEquals("lcm-service", result.username());
        assertEquals("Operator", result.roleId());
    }

    @Test
    @DisplayName("未启用托管账号时应跳过 provision")
    void shouldSkipProvisionWhenManagedAccountDisabled() {
        RedfishManagedAccountProvisioner provisioner = newProvisioner();

        CredentialProfile profile = new CredentialProfile();
        profile.setName("rack-a-openbmc");

        RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                provisioner.provision(new DiscoveredDevice(), profile).await().indefinitely();

        assertFalse(result.enabled());
        assertFalse(result.success());
        assertTrue(result.message().contains("disabled"));
    }

    @Test
    @DisplayName("托管账号 secret ref 未就绪时不应进入真实 provision")
    void shouldFailFastWhenManagedSecretsAreUnavailable() {
        RedfishManagedAccountProvisioner provisioner = newProvisioner();
        provisioner.secretRefResolver.envReader = key -> switch (key) {
            case "LCM_BMC_USERNAME" -> "admin";
            case "LCM_BMC_PASSWORD" -> "password";
            default -> null;
        };

        DiscoveredDevice device = new DiscoveredDevice();
        device.setBmcAddress("10.0.0.21");

        CredentialProfile profile = new CredentialProfile();
        profile.setName("rack-b-openbmc");
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");

        RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                provisioner.provision(device, profile).await().indefinitely();

        assertTrue(result.enabled());
        assertFalse(result.success());
        assertTrue(result.message().contains("managed account secret refs are not ready"));
    }

    private static RedfishManagedAccountProvisioner newProvisioner() {
        SecretRefResolver resolver = new SecretRefResolver();
        resolver.secretManagerClient = new VaultSecretClient();
        resolver.envReader = key -> switch (key) {
            case "LCM_BMC_USERNAME" -> "admin";
            case "LCM_BMC_PASSWORD" -> "password";
            case "LCM_BMC_MANAGED_USERNAME" -> "lcm-service";
            case "LCM_BMC_MANAGED_PASSWORD" -> "managed-secret";
            default -> null;
        };

        RedfishManagedAccountProvisioner provisioner = new RedfishManagedAccountProvisioner();
        provisioner.secretRefResolver = resolver;
        provisioner.objectMapper = new ObjectMapper();
        return provisioner;
    }
}
