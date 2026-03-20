package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultSecretClientTest {

    @Test
    @DisplayName("禁用 Vault 时应直接返回未解析")
    void shouldRejectVaultRefsWhenDisabled() {
        VaultSecretClient client = newClient();
        client.enabled = false;

        SecretManagerClient.SecretResolution resolution = client.resolve("vault://secret/bmc/node#username", "username");

        assertFalse(resolution.resolved());
        assertTrue(resolution.message().contains("disabled"));
    }

    @Test
    @DisplayName("应能解析 KV v2 secret")
    void shouldResolveKv2Secret() {
        VaultSecretClient client = newClient();
        client.transport = request -> Map.of(
                "data", Map.of(
                        "data", Map.of(
                                "username", "admin",
                                "password", "secret")));

        SecretManagerClient.SecretResolution resolution = client.resolve("vault://secret/bmc/node#username", "username");

        assertTrue(resolution.resolved());
        assertEquals("admin", resolution.value());
    }

    @Test
    @DisplayName("未显式指定 field 时应回退到默认字段名")
    void shouldFallbackToDefaultFieldName() {
        VaultSecretClient client = newClient();
        client.transport = request -> Map.of(
                "data", Map.of(
                        "data", Map.of(
                                "password", "super-secret")));

        SecretManagerClient.SecretResolution resolution = client.resolve("vault://secret/bmc/node", "password");

        assertTrue(resolution.resolved());
        assertEquals("super-secret", resolution.value());
    }

    @Test
    @DisplayName("query 中的 engine 参数应允许切换到 KV v1")
    void shouldSupportKv1OverrideFromQuery() {
        VaultSecretClient client = newClient();
        client.transport = request -> {
            assertEquals(1, request.engineVersion());
            assertEquals("https://vault.example/v1/secret/bmc/node", request.url());
            return Map.of("data", Map.of("username", "legacy-admin"));
        };

        SecretManagerClient.SecretResolution resolution = client.resolve(
                "vault://secret/bmc/node?engine=1#username",
                "username");

        assertTrue(resolution.resolved());
        assertEquals("legacy-admin", resolution.value());
    }

    private static VaultSecretClient newClient() {
        VaultSecretClient client = new VaultSecretClient();
        client.objectMapper = new ObjectMapper();
        client.enabled = true;
        client.address = Optional.of("https://vault.example");
        client.token = Optional.of("test-token");
        client.cacheTtlSeconds = 0;
        return client;
    }
}
