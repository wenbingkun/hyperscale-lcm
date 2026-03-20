package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 最小可用的 HashiCorp Vault KV 客户端。
 * 支持:
 * - vault://<mount>/<path>#<field>
 * - 全局 KV v1 / v2
 * - query 中的 version / engine 覆盖，例如 vault://secret/bmc/node?engine=1#username
 */
@ApplicationScoped
@Slf4j
public class VaultSecretClient implements SecretManagerClient {

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.claim.vault.enabled", defaultValue = "false")
    boolean enabled = false;

    @ConfigProperty(name = "lcm.claim.vault.address", defaultValue = "")
    Optional<String> address = Optional.empty();

    @ConfigProperty(name = "lcm.claim.vault.token", defaultValue = "")
    Optional<String> token = Optional.empty();

    @ConfigProperty(name = "lcm.claim.vault.namespace", defaultValue = "")
    Optional<String> namespace = Optional.empty();

    @ConfigProperty(name = "lcm.claim.vault.kv-engine-version", defaultValue = "2")
    int kvEngineVersion = 2;

    @ConfigProperty(name = "lcm.claim.vault.connect-timeout-ms", defaultValue = "3000")
    int connectTimeoutMs = 3000;

    @ConfigProperty(name = "lcm.claim.vault.read-timeout-ms", defaultValue = "5000")
    int readTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.vault.cache-ttl-seconds", defaultValue = "60")
    long cacheTtlSeconds = 60;

    VaultTransport transport;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public SecretManagerClient.SecretResolution resolve(String ref, String fieldName) {
        if (!enabled) {
            return SecretManagerClient.SecretResolution.unresolved("Vault secret refs are disabled by configuration.");
        }
        String configuredAddress = address.orElse("");
        if (configuredAddress.isBlank()) {
            return SecretManagerClient.SecretResolution.unresolved("Vault address is not configured.");
        }
        String configuredToken = token.orElse("");
        if (configuredToken.isBlank()) {
            return SecretManagerClient.SecretResolution.unresolved("Vault token is not configured.");
        }

        VaultReference reference;
        try {
            reference = VaultReference.parse(ref, fieldName, kvEngineVersion);
        } catch (IllegalArgumentException e) {
            return SecretManagerClient.SecretResolution.unresolved(e.getMessage());
        }

        String cacheKey = reference.cacheKey();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            return cached.resolution();
        }

        try {
            VaultTransport activeTransport = transport != null ? transport : this::readBlocking;
            Map<String, Object> document = activeTransport.read(new VaultRequest(
                    normalizeAddress(configuredAddress),
                    configuredToken,
                    namespace.orElse(""),
                    reference.mount(),
                    reference.secretPath(),
                    reference.field(),
                    reference.engineVersion(),
                    connectTimeoutMs,
                    readTimeoutMs));
            String value = extractValue(document, reference.engineVersion(), reference.field());
            SecretManagerClient.SecretResolution resolution = SecretManagerClient.SecretResolution.resolved(value);
            cache.put(cacheKey, new CacheEntry(resolution, Instant.now().plus(Duration.ofSeconds(cacheTtlSeconds))));
            return resolution;
        } catch (Exception e) {
            log.warn("Vault secret resolution failed for {}", ref, e);
            SecretManagerClient.SecretResolution resolution = SecretManagerClient.SecretResolution.unresolved(
                    "Vault secret lookup failed: " + rootCauseMessage(e));
            cache.put(cacheKey, new CacheEntry(resolution, Instant.now().plus(Duration.ofSeconds(cacheTtlSeconds))));
            return resolution;
        }
    }

    Map<String, Object> readBlocking(VaultRequest request) throws Exception {
        URL url = new URL(request.url());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(request.connectTimeoutMs());
        connection.setReadTimeout(request.readTimeoutMs());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Vault-Token", request.token());
        if (request.namespace() != null && !request.namespace().isBlank()) {
            connection.setRequestProperty("X-Vault-Namespace", request.namespace());
        }

        int status = connection.getResponseCode();
        if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
            throw new IllegalStateException("Vault returned HTTP " + status);
        }

        try (InputStream inputStream = connection.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    static String extractValue(Map<String, Object> document, int engineVersion, String field) {
        Object dataNode = document.get("data");
        if (!(dataNode instanceof Map<?, ?> dataMap)) {
            throw new IllegalStateException("Vault response does not contain a data object.");
        }

        Object secretNode = dataMap;
        if (engineVersion == 2) {
            secretNode = dataMap.get("data");
            if (!(secretNode instanceof Map<?, ?>)) {
                throw new IllegalStateException("Vault KV v2 response does not contain data.data.");
            }
        }

        Object value = ((Map<?, ?>) secretNode).get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalStateException("Vault field '" + field + "' is missing or empty.");
        }
        return stringValue;
    }

    private static String normalizeAddress(String rawAddress) {
        return rawAddress.replaceAll("/+$", "");
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface VaultTransport {
        Map<String, Object> read(VaultRequest request) throws Exception;
    }

    record VaultRequest(
            String address,
            String token,
            String namespace,
            String mount,
            String secretPath,
            String field,
            int engineVersion,
            int connectTimeoutMs,
            int readTimeoutMs) {

        String url() {
            String apiPath = engineVersion == 2
                    ? "/v1/" + mount + "/data/" + secretPath
                    : "/v1/" + mount + "/" + secretPath;
            return address + apiPath;
        }
    }

    record VaultReference(
            String mount,
            String secretPath,
            String field,
            int engineVersion) {

        static VaultReference parse(String ref, String defaultFieldName, int defaultEngineVersion) {
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException("Vault secret ref is empty.");
            }

            URI uri = URI.create(ref);
            if (!"vault".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Vault secret ref must use the vault:// scheme.");
            }

            String mount = uri.getHost();
            if (mount == null || mount.isBlank()) {
                throw new IllegalArgumentException("Vault secret ref must specify a mount, e.g. vault://secret/path#field.");
            }

            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                throw new IllegalArgumentException("Vault secret ref must include a secret path.");
            }

            String secretPath = path.startsWith("/") ? path.substring(1) : path;
            String field = uri.getFragment();
            if (field == null || field.isBlank()) {
                field = defaultFieldName;
            }

            int engineVersion = parseEngineVersion(uri.getQuery(), defaultEngineVersion);
            return new VaultReference(mount, secretPath, field, engineVersion);
        }

        String cacheKey() {
            return engineVersion + "|" + mount + "|" + secretPath + "|" + field;
        }

        private static int parseEngineVersion(String query, int defaultEngineVersion) {
            if (query == null || query.isBlank()) {
                return defaultEngineVersion;
            }

            for (String part : query.split("&")) {
                String[] tokens = part.split("=", 2);
                if (tokens.length != 2) {
                    continue;
                }
                String key = tokens[0];
                String value = tokens[1];
                if ("version".equalsIgnoreCase(key) || "engine".equalsIgnoreCase(key)) {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return defaultEngineVersion;
                    }
                }
            }

            return defaultEngineVersion;
        }
    }

    record CacheEntry(SecretManagerClient.SecretResolution resolution, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** @deprecated 使用 {@link SecretManagerClient.SecretResolution} 替代，保留此别名仅供过渡期外部代码兼容。 */
    @Deprecated(forRemoval = true)
    public record VaultSecretResolution(boolean resolved, String value, String message) {
        static VaultSecretResolution resolved(String value) {
            return new VaultSecretResolution(true, value, "Vault secret ref resolved.");
        }

        static VaultSecretResolution unresolved(String message) {
            return new VaultSecretResolution(false, null, message);
        }
    }
}
