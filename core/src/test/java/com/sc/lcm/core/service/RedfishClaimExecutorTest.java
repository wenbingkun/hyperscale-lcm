package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.support.RedfishMockServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RedfishClaimExecutorTest {

    @Test
    @DisplayName("OpenBMC profile 应通过真实 HTTPS claim 并推荐 openbmc 模板")
    void shouldValidateOpenBmcClaimOverHttps() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .build()) {
            RedfishClaimExecutor executor = newExecutor();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            CredentialProfile profile = new CredentialProfile();
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertTrue(result.success());
            assertEquals("ENV", result.credentialSource());
            assertEquals("OpenBMC", result.manufacturer());
            assertEquals("OpenBMC Reference Board", result.model());
            assertEquals("openbmc-baseline", result.recommendedTemplate());
            assertTrue(result.message().contains("validated"));
            assertTrue(server.findRequest("GET", "/redfish/v1/Systems").isPresent());
            assertTrue(server.findRequest("GET", "/redfish/v1/Systems/system")
                    .map(RedfishMockServer.CapturedRequest::authorization)
                    .orElse("")
                    .startsWith("Basic "));
        }
    }

    @Test
    @DisplayName("Dell profile 应通过真实 HTTPS claim 并推荐 dell-idrac 模板")
    void shouldValidateDellClaimAndRecommendTemplateOverHttps() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("dell-idrac")
                .build()) {
            RedfishClaimExecutor executor = newExecutor();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            CredentialProfile profile = new CredentialProfile();
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertTrue(result.success());
            assertEquals("ENV", result.credentialSource());
            assertEquals("Dell Inc.", result.manufacturer());
            assertEquals("PowerEdge R760", result.model());
            assertEquals("dell-idrac", result.recommendedTemplate());
            assertTrue(result.message().contains("validated"));
        }
    }

    @Test
    @DisplayName("secret ref 未就绪时不应执行真实 claim")
    void shouldFailFastWhenSecretsAreUnavailable() {
        RedfishClaimExecutor executor = newExecutor();
        executor.secretRefResolver.envReader = key -> null;

        DiscoveredDevice device = new DiscoveredDevice();
        device.setBmcAddress("10.0.0.11");

        CredentialProfile profile = new CredentialProfile();
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

        RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

        assertFalse(result.success());
        assertTrue(result.message().contains("not ready"));
    }

    @Test
    @DisplayName("凭据错误时应返回 401 claim 失败")
    void shouldFailWhenServerRejectsCredentials() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("dell-idrac")
                .build()) {
            RedfishClaimExecutor executor = newExecutor();
            executor.secretRefResolver.envReader = key -> switch (key) {
                case "LCM_BMC_USERNAME" -> "admin";
                case "LCM_BMC_PASSWORD" -> "wrong-password";
                default -> null;
            };

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            CredentialProfile profile = new CredentialProfile();
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertFalse(result.success());
            assertTrue(result.message().contains("401"));
            assertTrue(server.findRequest("GET", "/redfish/v1/Systems").isPresent());
        }
    }

    @Test
    @DisplayName("Redfish 读超时时应返回明确失败信息")
    void shouldFailWhenRedfishEndpointTimesOut() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .withResponseDelay(Duration.ofMillis(250))
                .build()) {
            RedfishClaimExecutor executor = newExecutor();
            executor.readTimeoutMs = 50;

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            CredentialProfile profile = new CredentialProfile();
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertFalse(result.success());
            assertTrue(result.message().toLowerCase().contains("timed out"));
        }
    }

    private static RedfishClaimExecutor newExecutor() {
        SecretRefResolver secretRefResolver = new SecretRefResolver();
        secretRefResolver.secretManagerClient = new VaultSecretClient();
        secretRefResolver.envReader = key -> switch (key) {
            case "LCM_BMC_USERNAME" -> "admin";
            case "LCM_BMC_PASSWORD" -> "password";
            default -> null;
        };

        RedfishTemplateCatalog templateCatalog = new RedfishTemplateCatalog();
        templateCatalog.objectMapper = new ObjectMapper();
        templateCatalog.templateDir = "";
        templateCatalog.invalidateCache();

        RedfishClaimExecutor executor = new RedfishClaimExecutor();
        executor.secretRefResolver = secretRefResolver;
        executor.redfishTemplateCatalog = templateCatalog;
        executor.objectMapper = new ObjectMapper();
        return executor;
    }
}
