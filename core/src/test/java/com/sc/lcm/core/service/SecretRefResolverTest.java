package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRefResolverTest {

    @Test
    @DisplayName("允许的 env secret ref 应可解析")
    void shouldResolveEnvSecretWhenEnvVarExists() {
        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> "LCM_BMC_USERNAME".equals(key) ? "admin" : null;

        SecretRefResolver.ResolvedSecret secret = resolver.resolveSecret("env://LCM_BMC_USERNAME", "username")
                .await().indefinitely();

        assertTrue(secret.isResolved());
        assertEquals("ENV", secret.getSource());
        assertEquals("admin", secret.getValue());
    }

    @Test
    @DisplayName("不在允许前缀内的 env secret ref 不应被接受")
    void shouldRejectEnvSecretOutsideAllowedPrefixes() {
        SecretRefResolver resolver = newResolver();

        SecretRefResolver.ResolvedSecret secret = resolver.resolveSecret("env://DATABASE_PASSWORD", "password")
                .await().indefinitely();

        assertFalse(secret.isResolved());
        assertTrue(secret.getMessage().contains("allowed prefixes"));
    }

    @Test
    @DisplayName("literal secret ref 默认应被拒绝")
    void shouldRejectLiteralSecretWhenDisabled() {
        SecretRefResolver resolver = newResolver();

        SecretRefResolver.ResolvedSecret secret = resolver.resolveSecret("literal://secret", "password")
                .await().indefinitely();

        assertFalse(secret.isResolved());
        assertEquals("LITERAL", secret.getSource());
    }

    @Test
    @DisplayName("启用 literal secret ref 后应允许本地调试")
    void shouldAllowLiteralSecretWhenEnabled() {
        SecretRefResolver resolver = newResolver();
        resolver.allowLiteralRefs = true;

        SecretRefResolver.ResolvedSecret secret = resolver.resolveSecret("literal://secret", "password")
                .await().indefinitely();

        assertTrue(secret.isResolved());
        assertEquals("secret", secret.getValue());
    }

    @Test
    @DisplayName("只有 username 和 password 都可解析时才应 ready")
    void shouldMarkCredentialMaterialReadyOnlyWhenBothRefsResolve() {
        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> "value";

        CredentialProfile profile = new CredentialProfile();
        profile.setName("rack-a");
        profile.setUsernameSecretRef("env://LCM_BMC_RACK_A_USERNAME");
        profile.setPasswordSecretRef("env://LCM_BMC_RACK_A_PASSWORD");

        SecretRefResolver.ResolvedCredentialMaterial material = resolver.resolve(profile).await().indefinitely();

        assertTrue(material.isReady());
        assertEquals("ENV", material.getCredentialSource());
    }

    @Test
    @DisplayName("启用托管账号后应能解析第二组 secret ref")
    void shouldResolveManagedAccountSecretRefs() {
        SecretRefResolver resolver = newResolver();
        resolver.envReader = key -> switch (key) {
            case "LCM_BMC_MANAGED_USERNAME" -> "lcm-service";
            case "LCM_BMC_MANAGED_PASSWORD" -> "managed-secret";
            default -> null;
        };

        CredentialProfile profile = new CredentialProfile();
        profile.setManagedAccountEnabled(true);
        profile.setManagedUsernameSecretRef("env://LCM_BMC_MANAGED_USERNAME");
        profile.setManagedPasswordSecretRef("env://LCM_BMC_MANAGED_PASSWORD");

        SecretRefResolver.ResolvedCredentialMaterial material = resolver.resolveManagedAccount(profile).await().indefinitely();

        assertTrue(material.isReady());
        assertEquals("ENV", material.getCredentialSource());
        assertEquals("lcm-service", material.getUsername().getValue());
    }

    private static SecretRefResolver newResolver() {
        SecretRefResolver resolver = new SecretRefResolver();
        resolver.secretManagerClient = new VaultSecretClient();
        return resolver;
    }
}
