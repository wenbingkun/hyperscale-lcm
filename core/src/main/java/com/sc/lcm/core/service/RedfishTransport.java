package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.RedfishAuthMode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Slf4j
public class RedfishTransport {

    static final String SERVICE_ROOT = "/redfish/v1";
    static final String SYSTEMS_PATH = SERVICE_ROOT + "/Systems";
    static final String MANAGERS_PATH = SERVICE_ROOT + "/Managers";
    static final String ACCOUNT_SERVICE_PATH = SERVICE_ROOT + "/AccountService";
    static final String SESSION_SERVICE_PATH = SERVICE_ROOT + "/SessionService";
    static final String SESSIONS_PATH = SESSION_SERVICE_PATH + "/Sessions";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<Integer> CAPABILITY_MISSING_CODES = Set.of(404, 405, 501);
    private static final AtomicBoolean HOSTNAME_VERIFICATION_DISABLED = new AtomicBoolean(false);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RedfishSessionManager sessionManager;

    @Inject
    jakarta.enterprise.inject.Instance<MetricsService> metricsServiceInstance;

    @ConfigProperty(name = "lcm.claim.redfish.retry-after-max-seconds", defaultValue = "5")
    long retryAfterMaxSeconds = 5;

    @ConfigProperty(name = "lcm.claim.redfish.session-ttl-seconds-max", defaultValue = "1800")
    long sessionTtlSecondsMax = 1800;

    public InspectionResult inspect(RequestOptions options) throws Exception {
        AuthContext authContext = open(options);
        Map<String, Object> systemsCollection = getJson(authContext, SYSTEMS_PATH, true);
        List<String> systemMembers = memberUris(systemsCollection);
        Map<String, Object> systemDocument = systemMembers.isEmpty()
                ? systemsCollection
                : getJson(authContext, systemMembers.getFirst(), true);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("sessionAuth", authContext.sessionSupported());
        capabilities.put("accountService", tryCapability(authContext, ACCOUNT_SERVICE_PATH));
        capabilities.put("managerActions", tryCapability(authContext, MANAGERS_PATH));

        List<String> resetActions = resolveResetActions(systemDocument);
        capabilities.put("powerControl", !resetActions.isEmpty());
        capabilities.put("resetActions", resetActions);
        capabilities.put("systemCount", systemMembers.isEmpty() ? 1 : systemMembers.size());

        return new InspectionResult(
                extractString(systemDocument, "Manufacturer"),
                extractString(systemDocument, "Model"),
                authContext.actualAuthMode(),
                capabilities);
    }

    public AuthContext open(RequestOptions options) throws Exception {
        if (options.authMode() == RedfishAuthMode.BASIC_ONLY) {
            return new AuthContext(options, "BASIC", false, null);
        }

        RedfishSessionManager.SessionKey sessionKey = RedfishSessionManager.keyFor(options);
        try {
            sessionManager.getOrCreate(sessionKey, () -> createSession(options));
            return new AuthContext(options, "SESSION", true, sessionKey);
        } catch (RedfishTransportException e) {
            if (options.authMode() == RedfishAuthMode.SESSION_ONLY || !isSessionFallbackCandidate(e)) {
                throw e;
            }
            log.debug("Redfish session auth unavailable for {}, falling back to Basic: {}",
                    options.endpoint(), e.failureCode());
            return new AuthContext(options, "BASIC", false, null);
        }
    }

    public Map<String, Object> getJson(AuthContext context, String path, boolean readOnly) throws Exception {
        RawResponse response = execute(context, "GET", path, null, readOnly);
        return parseJson(response, path);
    }

    public WriteResult writeJson(
            AuthContext context,
            String method,
            String path,
            Map<String, Object> payload,
            boolean readOnly) throws Exception {
        byte[] body = payload == null ? new byte[0] : objectMapper.writeValueAsBytes(payload);
        RawResponse response = execute(context, method, path, body, readOnly);
        if (!isSuccess(response.statusCode())) {
            throw asTransportException(response, method, path);
        }
        Map<String, Object> responseBody = parseOptionalJson(response.body());
        return new WriteResult(response.statusCode(), responseBody, firstHeader(response.headers(), "Location"));
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

    private RawResponse execute(
            AuthContext context,
            String method,
            String path,
            byte[] body,
            boolean readOnly) throws Exception {
        if ("SESSION".equals(context.actualAuthMode())) {
            return executeWithSession(context, method, path, body, readOnly, true);
        }
        return executeWithBasic(context.options(), method, path, body, readOnly, true);
    }

    private RawResponse executeWithSession(
            AuthContext context,
            String method,
            String path,
            byte[] body,
            boolean readOnly,
            boolean allowReauth) throws Exception {
        RedfishSessionManager.SessionKey key = context.sessionKey();
        RedfishSessionManager.CachedSession session = sessionManager.getOrCreate(key, () -> createSession(context.options()));
        RawResponse response = send(
                context.options(),
                method,
                path,
                body,
                Map.of("X-Auth-Token", session.token()),
                readOnly,
                true);
        if (response.statusCode() == 401 && allowReauth && readOnly) {
            sessionManager.invalidate(key);
            sessionManager.getOrCreate(key, () -> createSession(context.options()));
            recordSessionReauth();
            return executeWithSession(context, method, path, body, readOnly, false);
        }
        if (!isSuccess(response.statusCode())) {
            throw asTransportException(response, method, path);
        }
        return response;
    }

    private RawResponse executeWithBasic(
            RequestOptions options,
            String method,
            String path,
            byte[] body,
            boolean readOnly,
            boolean allowRetryAfter) throws Exception {
        RawResponse response = send(
                options,
                method,
                path,
                body,
                Map.of("Authorization", basicAuth(options.username(), options.password())),
                readOnly,
                allowRetryAfter);
        if (!isSuccess(response.statusCode())) {
            throw asTransportException(response, method, path);
        }
        return response;
    }

    private boolean tryCapability(AuthContext context, String path) throws Exception {
        try {
            getJson(context, path, true);
            return true;
        } catch (RedfishTransportException e) {
            if (CAPABILITY_MISSING_CODES.contains(e.statusCode())) {
                return false;
            }
            throw e;
        }
    }

    private RedfishSessionManager.CachedSession createSession(RequestOptions options) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "UserName", options.username(),
                "Password", options.password()));
        RawResponse response = send(options, "POST", SESSIONS_PATH, body, Map.of(), true, true);

        if (!isSuccess(response.statusCode())) {
            throw asSessionCreationException(response);
        }

        String token = firstHeader(response.headers(), "X-Auth-Token");
        if (token == null || token.isBlank()) {
            throw new RedfishTransportException("SESSION_TOKEN_MISSING",
                    502,
                    "Redfish session creation succeeded but response did not include X-Auth-Token.");
        }

        Map<String, Object> responseBody = parseOptionalJson(response.body());
        String sessionUri = firstHeader(response.headers(), "Location");
        if ((sessionUri == null || sessionUri.isBlank()) && responseBody != null) {
            sessionUri = extractString(responseBody, "@odata.id");
        }

        long ttlSeconds = resolveSessionTtlSeconds(options, token);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(ttlSeconds, 1));
        return new RedfishSessionManager.CachedSession(token, sessionUri, expiresAt, options);
    }

    private long resolveSessionTtlSeconds(RequestOptions options, String token) {
        try {
            RawResponse response = send(
                    options,
                    "GET",
                    SESSION_SERVICE_PATH,
                    null,
                    Map.of("X-Auth-Token", token),
                    true,
                    true);
            if (!isSuccess(response.statusCode())) {
                return sessionTtlSecondsMax;
            }
            Map<String, Object> payload = parseOptionalJson(response.body());
            Object rawTimeout = payload != null ? payload.get("SessionTimeout") : null;
            if (rawTimeout instanceof Number numberValue) {
                return Math.min(sessionTtlSecondsMax, numberValue.longValue());
            }
        } catch (Exception ignored) {
            return sessionTtlSecondsMax;
        }
        return sessionTtlSecondsMax;
    }

    private RawResponse send(
            RequestOptions options,
            String method,
            String path,
            byte[] body,
            Map<String, String> extraHeaders,
            boolean readOnly,
            boolean allowRetryAfter) throws Exception {
        HttpClient client = createClient(options);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(absoluteUrl(options.endpoint(), path)))
                .timeout(Duration.ofMillis(options.readTimeoutMs()))
                .header("Accept", "application/json");
        extraHeaders.forEach(builder::header);

        if (body != null && body.length > 0) {
            builder.header("Content-Type", "application/json");
        }

        HttpRequest.BodyPublisher publisher = body == null || body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(method, publisher);

        HttpRequest request = builder.build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            RawResponse rawResponse = new RawResponse(response.statusCode(), response.headers(), response.body());
            if (allowRetryAfter && readOnly && response.statusCode() == 503) {
                long retryAfterSeconds = retryAfterSeconds(response.headers());
                if (retryAfterSeconds > 0 && retryAfterSeconds <= retryAfterMaxSeconds) {
                    Thread.sleep(retryAfterSeconds * 1000L);
                    return send(options, method, path, body, extraHeaders, readOnly, false);
                }
            }
            return rawResponse;
        } catch (HttpTimeoutException e) {
            throw new RedfishTransportException("TIMED_OUT", 504,
                    method + " " + path + " timed out: " + e.getMessage(), e);
        } catch (ConnectException e) {
            throw new RedfishTransportException("CONNECT_FAILED", 503,
                    method + " " + path + " failed to connect: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RedfishTransportException("IO_ERROR", 502,
                    method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedfishTransportException("INTERRUPTED", 500,
                    method + " " + path + " was interrupted", e);
        }
    }

    private HttpClient createClient(RequestOptions options) throws GeneralSecurityException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(options.connectTimeoutMs()));
        if (options.insecure()) {
            disableHostnameVerificationForInsecureMode();
            builder.sslContext(insecureSslContext());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(sslParameters);
        }
        return builder.build();
    }

    private static void disableHostnameVerificationForInsecureMode() {
        if (HOSTNAME_VERIFICATION_DISABLED.compareAndSet(false, true)) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
    }

    private Map<String, Object> parseJson(RawResponse response, String path) throws Exception {
        if (!isSuccess(response.statusCode())) {
            throw asTransportException(response, "GET", path);
        }
        return objectMapper.readValue(response.body(), MAP_TYPE);
    }

    private Map<String, Object> parseOptionalJson(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return null;
        }
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return null;
        }
        return objectMapper.readValue(body, MAP_TYPE);
    }

    private RedfishTransportException asSessionCreationException(RawResponse response) {
        RedfishTransportException exception = asTransportException(response, "POST", SESSIONS_PATH);
        if (CAPABILITY_MISSING_CODES.contains(exception.statusCode())) {
            return new RedfishTransportException(
                    "SESSION_UNSUPPORTED",
                    exception.statusCode(),
                    exception.getMessage(),
                    exception);
        }
        return exception;
    }

    private RedfishTransportException asTransportException(RawResponse response, String method, String path) {
        String bodyMessage = response.bodyText();
        String message = method + " " + path + " returned HTTP " + response.statusCode();
        if (bodyMessage != null && !bodyMessage.isBlank()) {
            message += ": " + bodyMessage;
        }
        return new RedfishTransportException("HTTP_" + response.statusCode(), response.statusCode(), message);
    }

    private static boolean isSessionFallbackCandidate(RedfishTransportException exception) {
        return "SESSION_UNSUPPORTED".equals(exception.failureCode())
                || "SESSION_TOKEN_MISSING".equals(exception.failureCode());
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 204;
    }

    private static long retryAfterSeconds(HttpHeaders headers) {
        Optional<String> retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(retryAfter.get().trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        return headers.firstValue(name).orElse(null);
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> memberUris(Map<String, Object> document) {
        Object rawMembers = document.get("Members");
        if (!(rawMembers instanceof List<?> members)) {
            return List.of();
        }
        List<String> uris = new ArrayList<>();
        for (Object member : members) {
            if (member instanceof Map<?, ?> memberMap) {
                Object rawUri = memberMap.get("@odata.id");
                if (rawUri instanceof String uriValue && !uriValue.isBlank()) {
                    uris.add(uriValue);
                }
            }
        }
        return uris;
    }

    private static List<String> resolveResetActions(Map<String, Object> systemDocument) {
        Object rawActions = systemDocument.get("Actions");
        if (!(rawActions instanceof Map<?, ?> actions)) {
            return List.of();
        }
        Object rawResetAction = actions.get("#ComputerSystem.Reset");
        if (!(rawResetAction instanceof Map<?, ?> resetAction)) {
            return List.of();
        }
        Object rawAllowed = resetAction.get("ResetType@Redfish.AllowableValues");
        if (!(rawAllowed instanceof List<?> values)) {
            return extractString(resetAction, "target") == null ? List.of() : List.of("Reset");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                result.add(stringValue);
            }
        }
        return result;
    }

    private static String extractString(Map<?, ?> document, String field) {
        if (document == null) {
            return null;
        }
        Object value = document.get(field);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
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

    private void recordSessionReauth() {
        try {
            if (metricsServiceInstance != null && !metricsServiceInstance.isUnsatisfied()) {
                metricsServiceInstance.get().recordBmcSessionReauth("transport");
            }
        } catch (Exception ignored) {
            // metrics are best-effort, never fail the transport on instrumentation issues
        }
    }

    @PreDestroy
    void cleanupSessions() {
        for (Map.Entry<RedfishSessionManager.SessionKey, RedfishSessionManager.CachedSession> entry : sessionManager.snapshot()) {
            try {
                deleteSession(entry.getValue());
            } catch (Exception e) {
                log.debug("Best-effort Redfish session cleanup failed for {}: {}",
                        entry.getKey().endpoint(), e.getMessage());
            } finally {
                sessionManager.invalidate(entry.getKey());
            }
        }
    }

    private void deleteSession(RedfishSessionManager.CachedSession session) throws Exception {
        if (session == null || session.sessionUri() == null || session.sessionUri().isBlank()) {
            return;
        }
        send(
                session.options(),
                "DELETE",
                session.sessionUri(),
                null,
                Map.of("X-Auth-Token", session.token()),
                false,
                false);
    }

    public record RequestOptions(
            String endpoint,
            String username,
            String password,
            boolean insecure,
            int connectTimeoutMs,
            int readTimeoutMs,
            RedfishAuthMode authMode) {
    }

    public record AuthContext(
            RequestOptions options,
            String actualAuthMode,
            boolean sessionSupported,
            RedfishSessionManager.SessionKey sessionKey) {
    }

    public record InspectionResult(
            String manufacturer,
            String model,
            String authMode,
            Map<String, Object> capabilities) {
    }

    public record WriteResult(
            int statusCode,
            Map<String, Object> body,
            String location) {
    }

    record RawResponse(int statusCode, HttpHeaders headers, byte[] body) {
        String bodyText() {
            if (body == null || body.length == 0) {
                return null;
            }
            return new String(body, StandardCharsets.UTF_8).trim();
        }
    }

    public static class RedfishTransportException extends IOException {
        private final String failureCode;
        private final int statusCode;

        RedfishTransportException(String failureCode, int statusCode, String message) {
            super(message);
            this.failureCode = failureCode;
            this.statusCode = statusCode;
        }

        RedfishTransportException(String failureCode, int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.failureCode = failureCode;
            this.statusCode = statusCode;
        }

        public String failureCode() {
            return failureCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
