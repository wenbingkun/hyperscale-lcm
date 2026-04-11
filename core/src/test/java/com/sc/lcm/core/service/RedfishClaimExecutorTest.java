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
            assertEquals("BASIC", result.authMode());
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
            assertEquals("BASIC", result.authMode());
            assertTrue(result.message().contains("validated"));
        }
    }

    @Test
    @DisplayName("SESSION_PREFERRED 在支持 SessionService 时应优先走 session")
    void shouldPreferSessionAuthWhenSessionServiceIsAvailable() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .withSessionService()
                .build()) {
            RedfishClaimExecutor executor = newExecutor();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());

            CredentialProfile profile = new CredentialProfile();
            profile.setRedfishAuthMode("SESSION_PREFERRED");
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertTrue(result.success());
            assertEquals("SESSION", result.authMode());
            assertTrue(server.findRequest("POST", "/redfish/v1/SessionService/Sessions").isPresent());
            assertTrue(server.findRequest("GET", "/redfish/v1/Systems/system")
                    .map(RedfishMockServer.CapturedRequest::token)
                    .orElse("")
                    .startsWith("session-token-"));
        }
    }

    @Test
    @DisplayName("SESSION_ONLY 命中不支持 session 的设备时应明确失败")
    void shouldFailWhenSessionOnlyHitsUnsupportedDevice() {
        try (RedfishMockServer server = RedfishMockServer.builder()
                .withFixture("openbmc-baseline")
                .build()) {
            RedfishClaimExecutor executor = newExecutor();

            DiscoveredDevice device = new DiscoveredDevice();
            device.setBmcAddress(server.endpoint());
            device.setRedfishAuthModeOverride("SESSION_ONLY");

            CredentialProfile profile = new CredentialProfile();
            profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
            profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

            RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

            assertFalse(result.success());
            assertEquals("SESSION_UNSUPPORTED", result.authFailureCode());
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
            assertEquals("HTTP_401", result.authFailureCode());
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
            assertEquals("TIMED_OUT", result.authFailureCode());
            assertTrue(result.message().toLowerCase().contains("timed out"));
        }
    }

    @Test
    @DisplayName("absoluteUrl 应允许同 host 的绝对 URL")
    void shouldAllowAbsoluteUrlWithSameHost() {
        String endpoint = "https://bmc.example.com";
        String path = "https://bmc.example.com/redfish/v1/Systems/system";

        String url = RedfishClaimExecutor.absoluteUrl(endpoint, path);

        assertEquals(path, url);
    }

    @Test
    @DisplayName("absoluteUrl 应拒绝不同 host 的绝对 URL")
    void shouldRejectAbsoluteUrlWithDifferentHost() {
        String endpoint = "https://bmc.example.com";
        String path = "https://evil.example.com/redfish/v1/Systems/system";

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RedfishClaimExecutor.absoluteUrl(endpoint, path));

        assertTrue(exception.getMessage().contains("does not match endpoint"));
    }

    @Test
    @DisplayName("absoluteUrl 应允许标准相对路径")
    void shouldResolveStandardRelativePath() {
        String endpoint = "https://bmc.example.com";

        String url = RedfishClaimExecutor.absoluteUrl(endpoint, "/redfish/v1/Systems");

        assertEquals("https://bmc.example.com/redfish/v1/Systems", url);
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
        executor.redfishTransport = newRedfishTransport();
        return executor;
    }

    private static RedfishTransport newRedfishTransport() {
        RedfishTransport transport = new RedfishTransport();
        transport.objectMapper = new ObjectMapper();
        transport.sessionManager = new RedfishSessionManager();
        return transport;
    }
}
