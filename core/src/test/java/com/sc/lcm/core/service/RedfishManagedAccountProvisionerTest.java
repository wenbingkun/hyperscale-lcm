package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.support.RedfishMockServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedfishManagedAccountProvisionerTest {

    @Test
    @DisplayName("启用托管账号时应通过真实 HTTPS 创建标准 Redfish 账号")
    void shouldProvisionManagedAccountOverHttps() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .build()) {
            RedfishManagedAccountProvisioner provisioner = newProvisioner();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                    provisioner.provision(device, managedProfile("Operator")).await().indefinitely();

            assertTrue(result.enabled());
            assertTrue(result.success());
            assertEquals("lcm-service", result.username());
            assertEquals("Operator", result.roleId());
            assertTrue(server.findRequest("GET", "/redfish/v1/AccountService").isPresent());
            assertTrue(server.findRequest("GET", "/redfish/v1/AccountService/Accounts").isPresent());
            assertTrue(server.findRequest("POST", "/redfish/v1/AccountService/Accounts").isPresent());
            assertTrue(server.accounts().stream()
                    .anyMatch(account -> "lcm-service".equals(account.get("UserName")) && "Operator".equals(account.get("RoleId"))));
        }
    }

    @Test
    @DisplayName("托管账号已存在时应通过 PATCH 收敛密码与角色")
    void shouldPatchExistingManagedAccountOverHttps() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("dell-idrac")
                .withPreexistingAccount("lcm-service", "stale-password", "ReadOnly")
                .build()) {
            RedfishManagedAccountProvisioner provisioner = newProvisioner();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                    provisioner.provision(device, managedProfile("Administrator")).await().indefinitely();

            assertTrue(result.enabled());
            assertTrue(result.success());
            assertTrue(result.message().contains("reconciled"));
            assertTrue(server.findRequest("PATCH", "/redfish/v1/AccountService/Accounts/2").isPresent());
            assertTrue(server.accounts().stream()
                    .anyMatch(account -> "lcm-service".equals(account.get("UserName"))
                            && "managed-secret".equals(account.get("Password"))
                            && "Administrator".equals(account.get("RoleId"))));
        }
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

    @Test
    @DisplayName("缺失 AccountService 时应返回明确失败信息")
    void shouldFailWhenAccountServiceIsMissing() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .withoutAccountService()
                .build()) {
            RedfishManagedAccountProvisioner provisioner = newProvisioner();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            RedfishManagedAccountProvisioner.ManagedAccountProvisionResult result =
                    provisioner.provision(device, managedProfile("Administrator")).await().indefinitely();

            assertTrue(result.enabled());
            assertFalse(result.success());
            assertTrue(result.message().contains("404"));
        }
    }

    private static CredentialProfile managedProfile(String roleId) {
        CredentialProfile profile = new CredentialProfile();
        profile.setName("rack-a-openbmc");
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");
        profile.setManagedAccountRoleId(roleId);
        return profile;
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
