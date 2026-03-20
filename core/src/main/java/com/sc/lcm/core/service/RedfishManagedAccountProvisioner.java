package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 在首次 Redfish claim 成功后，按标准 Redfish AccountService 创建或收敛平台托管账号。
 * 当前仅覆盖标准路径，不做厂商 OEM 动作扩展。
 */
@ApplicationScoped
@Slf4j
public class RedfishManagedAccountProvisioner {

    private static final String SERVICE_ROOT = "/redfish/v1";
    private static final String ACCOUNT_SERVICE_PATH = SERVICE_ROOT + "/AccountService";
    private static final String FALLBACK_ACCOUNTS_PATH = ACCOUNT_SERVICE_PATH + "/Accounts";

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.claim.redfish.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.redfish.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    @ConfigProperty(name = "lcm.claim.redfish.insecure", defaultValue = "true")
    boolean insecure = true;

    @ConfigProperty(name = "lcm.claim.redfish.managed-account.default-role-id", defaultValue = "Administrator")
    String defaultRoleId = "Administrator";

    @ConfigProperty(name = "lcm.claim.redfish.managed-account.rotate-on-claim", defaultValue = "true")
    boolean rotateOnClaim = true;

    ProvisionTransport transport;

    public Uni<ManagedAccountProvisionResult> provision(DiscoveredDevice device, CredentialProfile profile) {
        if (profile == null) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.notEnabled("Credential profile not found."));
        }
        if (!profile.isManagedAccountEnabled()) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.notEnabled(
                    "Managed account provisioning is disabled for credential profile '" + profile.getName() + "'."));
        }

        String endpoint = resolveEndpoint(device);
        if (endpoint == null) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                    true,
                    null,
                    null,
                    null,
                    "BMC endpoint is missing on the discovered device."));
        }

        return secretRefResolver.resolve(profile)
                .onItem().transformToUni(bootstrapCredentials -> {
                    if (!bootstrapCredentials.isReady()) {
                        return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                                true,
                                endpoint,
                                null,
                                null,
                                "Managed account provisioning blocked because bootstrap claim credentials are not ready. "
                                        + bootstrapCredentials.getMessage()));
                    }

                    return secretRefResolver.resolveManagedAccount(profile)
                            .onItem().transformToUni(managedCredentials -> {
                                if (!managedCredentials.isReady()) {
                                    return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                                            true,
                                            endpoint,
                                            null,
                                            resolveRoleId(profile),
                                            "Managed account provisioning blocked because managed account secret refs are not ready. "
                                                    + managedCredentials.getMessage()));
                                }

                                ProvisionRequest request = new ProvisionRequest(
                                        endpoint,
                                        bootstrapCredentials.getUsername().getValue(),
                                        bootstrapCredentials.getPassword().getValue(),
                                        managedCredentials.getUsername().getValue(),
                                        managedCredentials.getPassword().getValue(),
                                        resolveRoleId(profile),
                                        insecure,
                                        connectTimeoutMs,
                                        readTimeoutMs);

                                ProvisionTransport activeTransport = transport != null ? transport : this::provisionBlocking;
                                return Uni.createFrom().item(() -> {
                                            try {
                                                return activeTransport.provision(request);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                        .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                                        .onFailure().recoverWithItem(error -> {
                                            String rootMessage = rootCauseMessage(error);
                                            log.warn("Managed BMC account provisioning failed for {}", endpoint, error);
                                            return ManagedAccountProvisionResult.failure(
                                                    true,
                                                    endpoint,
                                                    request.managedUsername(),
                                                    request.roleId(),
                                                    "Managed BMC account provisioning failed for " + endpoint + ": " + rootMessage);
                                        });
                            });
                });
    }

    ManagedAccountProvisionResult provisionBlocking(ProvisionRequest request) throws Exception {
        Map<String, Object> accountService = getJSON(request, ACCOUNT_SERVICE_PATH);
        String accountsUri = extractUri(accountService, "Accounts");
        if (accountsUri == null || accountsUri.isBlank()) {
            accountsUri = FALLBACK_ACCOUNTS_PATH;
        }

        Map<String, Object> accountsCollection = getJSON(request, accountsUri);
        String existingAccountUri = findExistingAccountUri(request, accountsCollection, request.managedUsername());
        if (existingAccountUri != null) {
            if (!rotateOnClaim) {
                return ManagedAccountProvisionResult.success(
                        true,
                        request.endpoint(),
                        request.managedUsername(),
                        request.roleId(),
                        "Managed BMC account '" + request.managedUsername() + "' already exists on " + request.endpoint() + ".");
            }

            Map<String, Object> patchPayload = new LinkedHashMap<>();
            patchPayload.put("Password", request.managedPassword());
            patchPayload.put("RoleId", request.roleId());
            patchPayload.put("Enabled", true);
            patchJSON(request, existingAccountUri, patchPayload);
            return ManagedAccountProvisionResult.success(
                    true,
                    request.endpoint(),
                    request.managedUsername(),
                    request.roleId(),
                    "Managed BMC account '" + request.managedUsername() + "' was reconciled on " + request.endpoint() + ".");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserName", request.managedUsername());
        payload.put("Password", request.managedPassword());
        payload.put("RoleId", request.roleId());
        payload.put("Enabled", true);

        postJSON(request, accountsUri, payload);
        return ManagedAccountProvisionResult.success(
                true,
                request.endpoint(),
                request.managedUsername(),
                request.roleId(),
                "Managed BMC account '" + request.managedUsername() + "' is ready on " + request.endpoint() + ".");
    }

    private String findExistingAccountUri(ProvisionRequest request, Map<String, Object> collection, String targetUsername)
            throws Exception {
        Object rawMembers = collection.get("Members");
        if (!(rawMembers instanceof java.util.List<?> members)) {
            return null;
        }

        for (Object member : members) {
            if (!(member instanceof Map<?, ?> memberMap)) {
                continue;
            }
            Object uri = memberMap.get("@odata.id");
            if (!(uri instanceof String accountUri) || accountUri.isBlank()) {
                continue;
            }

            Map<String, Object> accountDocument = getJSON(request, accountUri);
            String username = extractString(accountDocument, "UserName");
            if (targetUsername.equals(username)) {
                return accountUri;
            }
        }
        return null;
    }

    private Map<String, Object> getJSON(ProvisionRequest request, String path) throws Exception {
        URL url = new URL(absoluteUrl(request.endpoint(), path));
        HttpURLConnection connection = openConnection(url, request);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        int status = connection.getResponseCode();
        if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
            throw new ProvisionHttpException(status, "GET " + path + " returned " + status);
        }

        try (InputStream stream = connection.getInputStream()) {
            return objectMapper.readValue(stream, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    private void postJSON(ProvisionRequest request, String path, Map<String, Object> payload) throws Exception {
        writeJSON(request, "POST", path, payload);
    }

    private void patchJSON(ProvisionRequest request, String path, Map<String, Object> payload) throws Exception {
        writeJSON(request, "PATCH", path, payload);
    }

    private void writeJSON(ProvisionRequest request, String method, String path, Map<String, Object> payload) throws Exception {
        URL url = new URL(absoluteUrl(request.endpoint(), path));
        HttpURLConnection connection = openConnection(url, request);
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");

        try (OutputStream outputStream = connection.getOutputStream()) {
            objectMapper.writeValue(outputStream, payload);
        }

        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_OK
                || status == HttpURLConnection.HTTP_CREATED
                || status == HttpURLConnection.HTTP_ACCEPTED
                || status == HttpURLConnection.HTTP_NO_CONTENT) {
            return;
        }

        throw new ProvisionHttpException(status, method + " " + path + " returned " + status);
    }

    private HttpURLConnection openConnection(URL url, ProvisionRequest request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection https && request.insecure()) {
            https.setSSLSocketFactory(insecureSslContext().getSocketFactory());
            https.setHostnameVerifier(insecureHostnameVerifier());
        }

        connection.setConnectTimeout(request.connectTimeoutMs());
        connection.setReadTimeout(request.readTimeoutMs());
        connection.setRequestProperty("Authorization", basicAuth(request.bootstrapUsername(), request.bootstrapPassword()));
        return connection;
    }

    private String resolveRoleId(CredentialProfile profile) {
        if (profile.getManagedAccountRoleId() != null && !profile.getManagedAccountRoleId().isBlank()) {
            return profile.getManagedAccountRoleId();
        }
        return defaultRoleId;
    }

    private static String resolveEndpoint(DiscoveredDevice device) {
        if (device == null) {
            return null;
        }
        String candidate = device.getBmcAddress();
        if (candidate == null || candidate.isBlank()) {
            candidate = device.getIpAddress();
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
            candidate = "https://" + candidate;
        }
        return candidate.replaceAll("/+$", "");
    }

    private static String absoluteUrl(String endpoint, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (path.startsWith("/")) {
            return endpoint + path;
        }
        return endpoint + "/" + path;
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String extractUri(Map<String, Object> document, String field) {
        if (document == null) {
            return null;
        }
        Object rawNode = document.get(field);
        if (!(rawNode instanceof Map<?, ?> linkNode)) {
            return null;
        }
        Object uri = linkNode.get("@odata.id");
        return uri instanceof String value && !value.isBlank() ? value : null;
    }

    private static String extractString(Map<String, Object> document, String field) {
        if (document == null) {
            return null;
        }
        Object value = document.get(field);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }

    private static SSLContext insecureSslContext() throws GeneralSecurityException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAllCerts, new SecureRandom());
        return context;
    }

    private static HostnameVerifier insecureHostnameVerifier() {
        return (hostname, session) -> true;
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface ProvisionTransport {
        ManagedAccountProvisionResult provision(ProvisionRequest request) throws Exception;
    }

    public record ManagedAccountProvisionResult(
            boolean enabled,
            boolean success,
            String endpoint,
            String username,
            String roleId,
            String message) {

        static ManagedAccountProvisionResult notEnabled(String message) {
            return new ManagedAccountProvisionResult(false, false, null, null, null, message);
        }

        static ManagedAccountProvisionResult success(
                boolean enabled,
                String endpoint,
                String username,
                String roleId,
                String message) {
            return new ManagedAccountProvisionResult(enabled, true, endpoint, username, roleId, message);
        }

        static ManagedAccountProvisionResult failure(
                boolean enabled,
                String endpoint,
                String username,
                String roleId,
                String message) {
            return new ManagedAccountProvisionResult(enabled, false, endpoint, username, roleId, message);
        }
    }

    record ProvisionRequest(
            String endpoint,
            String bootstrapUsername,
            String bootstrapPassword,
            String managedUsername,
            String managedPassword,
            String roleId,
            boolean insecure,
            int connectTimeoutMs,
            int readTimeoutMs) {
    }

    static final class ProvisionHttpException extends IOException {
        ProvisionHttpException(int statusCode, String message) {
            super(message + " (status=" + statusCode + ")");
        }
    }
}
