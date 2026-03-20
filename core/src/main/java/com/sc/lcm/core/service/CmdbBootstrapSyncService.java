package com.sc.lcm.core.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.api.CredentialProfileResource.CredentialProfileRequest;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 在线同步企业 CMDB / 交付台账中的 bootstrap 凭据记录。
 *
 * 支持两种模式：
 * 1. 对齐模式：CMDB 直接返回 CredentialProfileRequest 数组；
 * 2. 映射模式：通过 mapping-file 将企业 API 字段映射到内部模型。
 */
@ApplicationScoped
@Slf4j
public class CmdbBootstrapSyncService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    BootstrapCredentialImportService bootstrapCredentialImportService;

    @ConfigProperty(name = "lcm.cmdb.sync.enabled", defaultValue = "false")
    boolean enabled = false;

    @ConfigProperty(name = "lcm.cmdb.sync.url", defaultValue = "")
    String url = "";

    @ConfigProperty(name = "lcm.cmdb.sync.auth-header-name", defaultValue = "Authorization")
    String authHeaderName = "Authorization";

    @ConfigProperty(name = "lcm.cmdb.sync.auth-header-value", defaultValue = "")
    String authHeaderValue = "";

    @ConfigProperty(name = "lcm.cmdb.sync.payload-root", defaultValue = "entries")
    String payloadRoot = "entries";

    @ConfigProperty(name = "lcm.cmdb.sync.source-type", defaultValue = "CMDB")
    String sourceType = "CMDB";

    @ConfigProperty(name = "lcm.cmdb.sync.mapping-file", defaultValue = "")
    String mappingFile = "";

    @ConfigProperty(name = "lcm.cmdb.sync.max-pages", defaultValue = "20")
    int maxPages = 20;

    @ConfigProperty(name = "lcm.cmdb.sync.connect-timeout-ms", defaultValue = "3000")
    int connectTimeoutMs = 3000;

    @ConfigProperty(name = "lcm.cmdb.sync.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    SyncTransport transport;

    public Uni<SyncResult> syncNow() {
        if (!enabled) {
            return Uni.createFrom().item(SyncResult.skipped("CMDB sync is disabled by configuration."));
        }

        SyncTransport activeTransport = transport != null ? transport : this::readBlocking;

        return Uni.createFrom().item(() -> {
                    try {
                        return syncBlocking(activeTransport);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .onItem().transformToUni(plan -> bootstrapCredentialImportService.importEntries(plan.entries())
                        .map(importResult -> SyncResult.success(
                                plan.endpoint(),
                                plan.sourceType(),
                                plan.fetched(),
                                importResult.created(),
                                importResult.updated(),
                                importResult.skipped(),
                                "CMDB bootstrap sync completed.")))
                .onFailure().recoverWithItem(error -> {
                    String message = rootCauseMessage(error);
                    log.warn("CMDB bootstrap sync failed", error);
                    return SyncResult.failure("CMDB bootstrap sync failed: " + message);
                });
    }

    @Scheduled(cron = "{lcm.cmdb.sync.schedule}")
    public void scheduledSync() {
        if (!enabled) {
            return;
        }

        syncNow().subscribe().with(
                result -> {
                    if (result.status() == SyncStatus.SUCCESS) {
                        log.info("✅ CMDB bootstrap sync complete: fetched={}, created={}, updated={}, skipped={}",
                                result.fetched(),
                                result.created(),
                                result.updated(),
                                result.skipped());
                    } else if (result.status() == SyncStatus.SKIPPED) {
                        log.info("CMDB bootstrap sync skipped: {}", result.message());
                    } else {
                        log.warn("CMDB bootstrap sync failed: {}", result.message());
                    }
                },
                error -> log.error("CMDB bootstrap sync crashed", error));
    }

    SyncPlan syncBlocking(SyncTransport activeTransport) throws Exception {
        CmdbMappingProfile profile = loadMappingProfile(objectMapper, mappingFile);
        String startUrl = determineStartUrl(profile, url);
        if (!hasText(startUrl)) {
            throw new IllegalStateException("CMDB sync URL is not configured.");
        }

        List<CredentialProfileRequest> aggregated = new ArrayList<>();
        String currentUrl = startUrl;
        int pages = 0;

        while (hasText(currentUrl)) {
            if (pages >= maxPages) {
                throw new IllegalStateException("CMDB sync exceeded max pages: " + maxPages);
            }

            SyncRequest request = new SyncRequest(currentUrl, authHeaderName, authHeaderValue, connectTimeoutMs, readTimeoutMs);
            JsonNode document = activeTransport.fetch(request);

            List<CredentialProfileRequest> pageEntries = profile == null
                    ? normalizeEntries(extractEntries(objectMapper, document, payloadRoot), sourceType)
                    : mapEntries(objectMapper, document, profile, payloadRoot, sourceType);
            aggregated.addAll(pageEntries);

            currentUrl = profile != null
                    ? resolveNextPageUrl(currentUrl, document, profile.nextPagePath)
                    : null;
            pages++;
        }

        String resolvedSourceType = profile != null && hasText(profile.defaults != null ? profile.defaults.sourceType : null)
                ? normalizeSourceType(profile.defaults.sourceType)
                : normalizeSourceType(sourceType);
        return new SyncPlan(startUrl, resolvedSourceType, aggregated.size(), aggregated);
    }

    JsonNode readBlocking(SyncRequest request) throws Exception {
        URL remote = new URL(request.url());
        HttpURLConnection connection = (HttpURLConnection) remote.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(request.connectTimeoutMs());
        connection.setReadTimeout(request.readTimeoutMs());
        connection.setRequestProperty("Accept", "application/json");

        if (request.authHeaderName() != null
                && !request.authHeaderName().isBlank()
                && request.authHeaderValue() != null
                && !request.authHeaderValue().isBlank()) {
            connection.setRequestProperty(request.authHeaderName(), request.authHeaderValue());
        }

        int status = connection.getResponseCode();
        if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
            throw new IllegalStateException("CMDB returned HTTP " + status);
        }

        try (InputStream stream = connection.getInputStream()) {
            return objectMapper.readTree(stream);
        }
    }

    static CmdbMappingProfile loadMappingProfile(ObjectMapper objectMapper, String mappingFile) throws Exception {
        if (!hasText(mappingFile)) {
            return null;
        }

        Path path = Path.of(mappingFile);
        if (!Files.exists(path)) {
            throw new IllegalStateException("CMDB mapping file not found: " + path);
        }
        return objectMapper.readValue(path.toFile(), CmdbMappingProfile.class);
    }

    static List<CredentialProfileRequest> normalizeEntries(List<CredentialProfileRequest> entries, String defaultSourceType) {
        String normalizedSourceType = normalizeSourceType(defaultSourceType);
        return entries.stream()
                .map(entry -> new CredentialProfileRequest(
                        entry.name(),
                        entry.protocol(),
                        entry.enabled(),
                        entry.autoClaim(),
                        entry.priority(),
                        hasText(entry.sourceType()) ? entry.sourceType() : normalizedSourceType,
                        entry.externalRef(),
                        entry.vendorPattern(),
                        entry.modelPattern(),
                        entry.subnetCidr(),
                        entry.deviceType(),
                        entry.hostnamePattern(),
                        entry.ipAddressPattern(),
                        entry.macAddressPattern(),
                        entry.redfishTemplate(),
                        entry.usernameSecretRef(),
                        entry.passwordSecretRef(),
                        entry.managedAccountEnabled(),
                        entry.managedUsernameSecretRef(),
                        entry.managedPasswordSecretRef(),
                        entry.managedAccountRoleId(),
                        entry.description()))
                .toList();
    }

    static List<CredentialProfileRequest> mapEntries(
            ObjectMapper objectMapper,
            JsonNode document,
            CmdbMappingProfile profile,
            String fallbackEntriesPath,
            String defaultSourceType) {
        JsonNode entriesNode = resolveEntriesNode(document, hasText(profile.entriesPath) ? profile.entriesPath : fallbackEntriesPath);
        if (entriesNode == null || entriesNode.isNull()) {
            throw new IllegalArgumentException("CMDB response does not contain a bootstrap entries array.");
        }
        if (!entriesNode.isArray()) {
            throw new IllegalArgumentException("CMDB bootstrap payload must be a JSON array.");
        }

        List<CredentialProfileRequest> mapped = new ArrayList<>();
        for (JsonNode entry : entriesNode) {
            mapped.add(mapEntry(objectMapper, entry, profile, defaultSourceType));
        }
        return mapped;
    }

    static CredentialProfileRequest mapEntry(
            ObjectMapper objectMapper,
            JsonNode entry,
            CmdbMappingProfile profile,
            String defaultSourceType) {
        CmdbFieldMap fields = profile.fields != null ? profile.fields : new CmdbFieldMap();
        CmdbDefaults defaults = profile.defaults != null ? profile.defaults : new CmdbDefaults();

        String resolvedSourceType = firstText(
                readString(entry, fields.sourceType),
                defaults.sourceType,
                defaultSourceType);
        String hostnamePattern = resolvePatternField(
                "hostname_pattern",
                readString(entry, fields.hostnamePattern),
                defaults.hostnamePattern,
                profile);
        String ipAddressPattern = resolvePatternField(
                "ip_address_pattern",
                readString(entry, fields.ipAddressPattern),
                defaults.ipAddressPattern,
                profile);
        String macAddressPattern = resolvePatternField(
                "mac_address_pattern",
                readString(entry, fields.macAddressPattern),
                defaults.macAddressPattern,
                profile);

        return new CredentialProfileRequest(
                firstText(readString(entry, fields.name), defaults.name),
                firstText(readString(entry, fields.protocol), defaults.protocol),
                firstBoolean(readBoolean(entry, fields.enabled), defaults.enabled),
                firstBoolean(readBoolean(entry, fields.autoClaim), defaults.autoClaim),
                firstInteger(readInteger(entry, fields.priority), defaults.priority),
                normalizeSourceType(resolvedSourceType),
                firstText(readString(entry, fields.externalRef), defaults.externalRef),
                firstText(readString(entry, fields.vendorPattern), defaults.vendorPattern),
                firstText(readString(entry, fields.modelPattern), defaults.modelPattern),
                firstText(readString(entry, fields.subnetCidr), defaults.subnetCidr),
                firstText(readString(entry, fields.deviceType), defaults.deviceType),
                hostnamePattern,
                ipAddressPattern,
                macAddressPattern,
                firstText(readString(entry, fields.redfishTemplate), defaults.redfishTemplate),
                firstText(readString(entry, fields.usernameSecretRef), defaults.usernameSecretRef),
                firstText(readString(entry, fields.passwordSecretRef), defaults.passwordSecretRef),
                firstBoolean(readBoolean(entry, fields.managedAccountEnabled), defaults.managedAccountEnabled),
                firstText(readString(entry, fields.managedUsernameSecretRef), defaults.managedUsernameSecretRef),
                firstText(readString(entry, fields.managedPasswordSecretRef), defaults.managedPasswordSecretRef),
                firstText(readString(entry, fields.managedAccountRoleId), defaults.managedAccountRoleId),
                firstText(readString(entry, fields.description), defaults.description));
    }

    static List<CredentialProfileRequest> extractEntries(ObjectMapper objectMapper, JsonNode document, String payloadRoot) {
        JsonNode entriesNode = resolveEntriesNode(document, payloadRoot);
        if (entriesNode == null || entriesNode.isNull()) {
            throw new IllegalArgumentException("CMDB response does not contain a bootstrap entries array.");
        }
        if (!entriesNode.isArray()) {
            throw new IllegalArgumentException("CMDB bootstrap payload must be a JSON array.");
        }
        return objectMapper.convertValue(entriesNode, new TypeReference<List<CredentialProfileRequest>>() {
        });
    }

    static String resolveNextPageUrl(String currentUrl, JsonNode document, String nextPagePath) {
        String next = readString(document, nextPagePath);
        if (!hasText(next)) {
            return null;
        }
        return URI.create(currentUrl).resolve(next).toString();
    }

    private static JsonNode resolveEntriesNode(JsonNode document, String payloadRoot) {
        JsonNode current = resolvePath(document, payloadRoot);
        if (current != null && current.isArray()) {
            return current;
        }

        JsonNode fallback = resolvePath(document, "entries");
        if (fallback != null && fallback.isArray()) {
            return fallback;
        }
        return current;
    }

    private static JsonNode resolvePath(JsonNode document, String path) {
        if (document == null || document.isNull()) {
            return null;
        }
        if (!hasText(path)) {
            return document;
        }

        JsonNode current = document;
        for (String part : path.split("\\.")) {
            if (!hasText(part)) {
                continue;
            }
            if (current == null || current.isNull()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static String readString(JsonNode node, String path) {
        JsonNode value = resolvePath(node, path);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static Boolean readBoolean(JsonNode node, String path) {
        JsonNode value = resolvePath(node, path);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String raw = value.asText();
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                return Boolean.parseBoolean(raw);
            }
        }
        return null;
    }

    private static Integer readInteger(JsonNode node, String path) {
        JsonNode value = resolvePath(node, path);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String determineStartUrl(CmdbMappingProfile profile, String configuredUrl) {
        if (profile != null && hasText(profile.url)) {
            return profile.url;
        }
        return configuredUrl;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Boolean... values) {
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer firstInteger(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String resolvePatternField(
            String fieldName,
            String mappedValue,
            String defaultValue,
            CmdbMappingProfile profile) {
        if (hasText(mappedValue)) {
            return shouldTreatAsLiteralPattern(fieldName, profile)
                    ? toExactRegex(mappedValue)
                    : mappedValue;
        }
        return defaultValue;
    }

    private static boolean shouldTreatAsLiteralPattern(String fieldName, CmdbMappingProfile profile) {
        if (profile == null || profile.literalPatternFields == null) {
            return false;
        }
        return profile.literalPatternFields.stream()
                .filter(CmdbBootstrapSyncService::hasText)
                .anyMatch(fieldName::equals);
    }

    private static String toExactRegex(String value) {
        return "^" + Pattern.quote(value) + "$";
    }

    private static String normalizeSourceType(String rawSourceType) {
        if (!hasText(rawSourceType)) {
            return "CMDB";
        }
        return rawSourceType.trim().toUpperCase();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface SyncTransport {
        JsonNode fetch(SyncRequest request) throws Exception;
    }

    record SyncRequest(
            String url,
            String authHeaderName,
            String authHeaderValue,
            int connectTimeoutMs,
            int readTimeoutMs) {
    }

    record SyncPlan(
            String endpoint,
            String sourceType,
            int fetched,
            List<CredentialProfileRequest> entries) {
    }

    public record SyncResult(
            SyncStatus status,
            String endpoint,
            String sourceType,
            int fetched,
            int created,
            int updated,
            int skipped,
            String message) {

        static SyncResult success(
                String endpoint,
                String sourceType,
                int fetched,
                int created,
                int updated,
                int skipped,
                String message) {
            return new SyncResult(SyncStatus.SUCCESS, endpoint, sourceType, fetched, created, updated, skipped, message);
        }

        static SyncResult skipped(String message) {
            return new SyncResult(SyncStatus.SKIPPED, null, null, 0, 0, 0, 0, message);
        }

        static SyncResult failure(String message) {
            return new SyncResult(SyncStatus.FAILURE, null, null, 0, 0, 0, 0, message);
        }
    }

    public enum SyncStatus {
        SUCCESS,
        SKIPPED,
        FAILURE
    }

    static final class CmdbMappingProfile {
        public String url;

        @JsonProperty("entries_path")
        public String entriesPath;

        @JsonProperty("next_page_path")
        public String nextPagePath;

        @JsonProperty("literal_pattern_fields")
        public List<String> literalPatternFields;

        public CmdbDefaults defaults;
        public CmdbFieldMap fields;
    }

    static final class CmdbDefaults {
        public String name;
        public String protocol;
        public Boolean enabled;
        public Boolean autoClaim;
        public Integer priority;
        public String sourceType;
        public String externalRef;
        public String vendorPattern;
        public String modelPattern;
        public String subnetCidr;
        public String deviceType;
        public String hostnamePattern;
        public String ipAddressPattern;
        public String macAddressPattern;
        public String redfishTemplate;
        public String usernameSecretRef;
        public String passwordSecretRef;
        public Boolean managedAccountEnabled;
        public String managedUsernameSecretRef;
        public String managedPasswordSecretRef;
        public String managedAccountRoleId;
        public String description;
    }

    static final class CmdbFieldMap {
        public String name;
        public String protocol;
        public String enabled;
        public String autoClaim;
        public String priority;

        @JsonProperty("source_type")
        public String sourceType;

        @JsonProperty("external_ref")
        public String externalRef;

        @JsonProperty("vendor_pattern")
        public String vendorPattern;

        @JsonProperty("model_pattern")
        public String modelPattern;

        @JsonProperty("subnet_cidr")
        public String subnetCidr;

        @JsonProperty("device_type")
        public String deviceType;

        @JsonProperty("hostname_pattern")
        public String hostnamePattern;

        @JsonProperty("ip_address_pattern")
        public String ipAddressPattern;

        @JsonProperty("mac_address_pattern")
        public String macAddressPattern;

        @JsonProperty("redfish_template")
        public String redfishTemplate;

        @JsonProperty("username_secret_ref")
        public String usernameSecretRef;

        @JsonProperty("password_secret_ref")
        public String passwordSecretRef;

        @JsonProperty("managed_account_enabled")
        public String managedAccountEnabled;

        @JsonProperty("managed_username_secret_ref")
        public String managedUsernameSecretRef;

        @JsonProperty("managed_password_secret_ref")
        public String managedPasswordSecretRef;

        @JsonProperty("managed_account_role_id")
        public String managedAccountRoleId;

        public String description;
    }
}
