package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedfishClaimExecutorTest {

    @Test
    @DisplayName("claim 执行成功时应返回已认证结果和推荐模板")
    void shouldValidateClaimAndRecommendTemplate() {
        RedfishClaimExecutor executor = newExecutor();
        executor.transport = request -> new RedfishClaimExecutor.ProbeResponse("Dell Inc.", "PowerEdge R760");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setBmcAddress("10.0.0.10");

        CredentialProfile profile = new CredentialProfile();
        profile.setUsernameSecretRef("env://LCM_BMC_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_PASSWORD");

        RedfishClaimExecutor.ClaimExecutionResult result = executor.execute(device, profile).await().indefinitely();

        assertTrue(result.success());
        assertEquals("ENV", result.credentialSource());
        assertEquals("dell-idrac", result.recommendedTemplate());
        assertTrue(result.message().contains("validated"));
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
