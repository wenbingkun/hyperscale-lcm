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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 执行首次 Redfish claim。
 * 当前阶段先验证 BMC 登录是否成功，并回传真实厂商/型号信息。
 */
@ApplicationScoped
@Slf4j
public class RedfishClaimExecutor {

    private static final String SERVICE_ROOT = "/redfish/v1";

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    RedfishTemplateCatalog redfishTemplateCatalog;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.claim.redfish.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.redfish.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    @ConfigProperty(name = "lcm.claim.redfish.insecure", defaultValue = "true")
    boolean insecure = true;

    ProbeTransport transport;

    public Uni<ClaimExecutionResult> execute(DiscoveredDevice device, CredentialProfile profile) {
        if (device == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(null, null, "Device not found."));
        }
        if (profile == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(resolveEndpoint(device), null,
                    "Credential profile not found."));
        }

        String endpoint = resolveEndpoint(device);
        if (endpoint == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(null, null,
                    "BMC endpoint is missing on the discovered device."));
        }

        return secretRefResolver.resolve(profile)
                .onItem().transformToUni(credentials -> {
                    if (!credentials.isReady()) {
                        return Uni.createFrom().item(ClaimExecutionResult.failure(
                                endpoint,
                                credentials.getCredentialSource(),
                                "Claim blocked because secret refs are not ready. " + credentials.getMessage()));
                    }

                    ProbeRequest request = new ProbeRequest(
                            endpoint,
                            credentials.getUsername().getValue(),
                            credentials.getPassword().getValue(),
                            insecure,
                            connectTimeoutMs,
                            readTimeoutMs);

                    ProbeTransport activeTransport = transport != null ? transport : this::probeBlocking;

                    return Uni.createFrom().item(() -> {
                                try {
                                    return activeTransport.probe(request);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                            .map(response -> {
                                String recommendedTemplate = preferConfiguredTemplate(
                                        profile.getRedfishTemplate(),
                                        redfishTemplateCatalog.recommend(response.manufacturer(), response.model())
                                                .map(RedfishTemplateCatalog.TemplateMetadata::name)
                                                .orElse(null));
                                String message = "Redfish authentication validated for " + endpoint + ".";
                                if (recommendedTemplate != null && !recommendedTemplate.isBlank()) {
                                    message += " Recommended template '" + recommendedTemplate + "'.";
                                }
                                return ClaimExecutionResult.success(
                                        endpoint,
                                        credentials.getCredentialSource(),
                                        response.manufacturer(),
                                        response.model(),
                                        recommendedTemplate,
                                        message);
                            })
                            .onFailure().recoverWithItem(error -> {
                                String rootMessage = rootCauseMessage(error);
                                log.warn("Redfish claim failed for {}", endpoint, error);
                                return ClaimExecutionResult.failure(
                                        endpoint,
                                        credentials.getCredentialSource(),
                                        "Redfish claim failed for " + endpoint + ": " + rootMessage);
                            });
                });
    }

    ProbeResponse probeBlocking(ProbeRequest request) throws Exception {
        Map<String, Object> systems = getJSON(request, SERVICE_ROOT + "/Systems");
        Map<String, Object> system = unwrapPrimaryMember(request, systems);

        String manufacturer = extractString(system, "Manufacturer");
        String model = extractString(system, "Model");
        return new ProbeResponse(manufacturer, model);
    }

    private Map<String, Object> unwrapPrimaryMember(ProbeRequest request, Map<String, Object> document) throws Exception {
        String memberUri = firstMemberUri(document);
        if (memberUri == null) {
            return document;
        }
        return getJSON(request, memberUri);
    }

    private Map<String, Object> getJSON(ProbeRequest request, String path) throws Exception {
        URL url = new URL(absoluteUrl(request.endpoint(), path));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection https && request.insecure()) {
            https.setSSLSocketFactory(insecureSslContext().getSocketFactory());
            https.setHostnameVerifier(insecureHostnameVerifier());
        }

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(request.connectTimeoutMs());
        connection.setReadTimeout(request.readTimeoutMs());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", basicAuth(request.username(), request.password()));

        try {
            int status = connection.getResponseCode();
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                log.warn("Redfish GET {} returned HTTP {}", url, status);
                throw new ProbeHttpException(status, "GET " + path + " returned " + status);
            }

            try (InputStream stream = connection.getInputStream()) {
                return objectMapper.readValue(stream, new TypeReference<Map<String, Object>>() {
                });
            }
        } catch (IOException e) {
            log.warn("Redfish GET {} failed: {}", url, e.getMessage());
            throw e;
        }
    }

    private static String resolveEndpoint(DiscoveredDevice device) {
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

    static String absoluteUrl(String endpoint, String path) {
        URI endpointUri = URI.create(endpoint).normalize();
        URI pathUri = URI.create(path).normalize();

        if (pathUri.isAbsolute()) {
            if (!isSameOrigin(endpointUri, pathUri)) {
                throw new IllegalArgumentException("Redfish path host does not match endpoint.");
            }
            return pathUri.toString();
        }

        URI baseUri = endpointUri;
        if (!path.startsWith("/")) {
            baseUri = URI.create(endpointUri.toString() + "/");
        }

        return baseUri.resolve(pathUri).normalize().toString();
    }

    private static boolean isSameOrigin(URI endpointUri, URI targetUri) {
        return endpointUri.getScheme().equalsIgnoreCase(targetUri.getScheme())
                && endpointUri.getHost().equalsIgnoreCase(targetUri.getHost())
                && effectivePort(endpointUri) == effectivePort(targetUri);
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstMemberUri(Map<String, Object> document) {
        Object rawMembers = document.get("Members");
        if (!(rawMembers instanceof java.util.List<?> members) || members.isEmpty()) {
            return null;
        }
        Object first = members.getFirst();
        if (!(first instanceof Map<?, ?> firstMap)) {
            return null;
        }
        Object uri = firstMap.get("@odata.id");
        return uri instanceof String value ? value : null;
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

    private static String preferConfiguredTemplate(String configured, String recommended) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return recommended;
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface ProbeTransport {
        ProbeResponse probe(ProbeRequest request) throws Exception;
    }

    public record ClaimExecutionResult(
            boolean success,
            String endpoint,
            String credentialSource,
            String manufacturer,
            String model,
            String recommendedTemplate,
            String message) {

        static ClaimExecutionResult success(
                String endpoint,
                String credentialSource,
                String manufacturer,
                String model,
                String recommendedTemplate,
                String message) {
            return new ClaimExecutionResult(true, endpoint, credentialSource, manufacturer, model, recommendedTemplate, message);
        }

        static ClaimExecutionResult failure(String endpoint, String credentialSource, String message) {
            return new ClaimExecutionResult(false, endpoint, credentialSource, null, null, null, message);
        }
    }

    record ProbeRequest(
            String endpoint,
            String username,
            String password,
            boolean insecure,
            int connectTimeoutMs,
            int readTimeoutMs) {
    }

    record ProbeResponse(String manufacturer, String model) {
    }

    static final class ProbeHttpException extends IOException {
        ProbeHttpException(int statusCode, String message) {
            super(message + " (status=" + statusCode + ")");
        }
    }
}
