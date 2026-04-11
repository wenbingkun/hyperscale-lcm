package com.sc.lcm.core.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedfishMockServer implements AutoCloseable {

    private static final String SERVICE_ROOT = "/redfish/v1";
    private static final String SESSION_SERVICE_PATH = SERVICE_ROOT + "/SessionService";
    private static final String SESSIONS_PATH = SESSION_SERVICE_PATH + "/Sessions";
    private static final String ACCOUNT_SERVICE_PATH = "/redfish/v1/AccountService";
    private static final String ACCOUNTS_PATH = ACCOUNT_SERVICE_PATH + "/Accounts";
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, Object>>> FIXTURE_BUNDLE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final String expectedAuthorization;
    private final boolean accountServiceEnabled;
    private final boolean sessionServiceEnabled;
    private final Duration responseDelay;
    private final Map<String, Map<String, Object>> fixtures;
    private final Map<String, ActionResponse> actionResponses;
    private final List<Map<String, Object>> accounts = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> sessions = new CopyOnWriteArrayList<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextAccountId = new AtomicInteger(1);
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private final HttpsServer server;

    private RedfishMockServer(Builder builder) {
        bootstrapUsername = builder.bootstrapUsername;
        bootstrapPassword = builder.bootstrapPassword;
        expectedAuthorization = basicAuth(builder.bootstrapUsername, builder.bootstrapPassword);
        accountServiceEnabled = builder.accountServiceEnabled;
        sessionServiceEnabled = builder.sessionServiceEnabled;
        responseDelay = builder.responseDelay != null ? builder.responseDelay : Duration.ZERO;
        Map<String, Map<String, Object>> resolvedFixtures = new LinkedHashMap<>(loadFixtureBundle(builder.fixtureName, objectMapper));
        resolvedFixtures.putAll(builder.fixtureOverrides);
        fixtures = resolvedFixtures;
        actionResponses = new LinkedHashMap<>(builder.actionResponses);
        initializeAccounts(builder);
        server = createHttpsServer();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String endpoint() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    public Optional<CapturedRequest> findRequest(String method, String path) {
        return requests.stream()
                .filter(request -> request.method().equalsIgnoreCase(method) && request.path().equals(path))
                .findFirst();
    }

    public List<Map<String, Object>> accounts() {
        return accounts.stream()
                .<Map<String, Object>>map(account -> new LinkedHashMap<>(account))
                .toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void initializeAccounts(Builder builder) {
        addAccount(builder.bootstrapUsername, builder.bootstrapPassword, "Administrator", true);
        for (Builder.AccountSeed seed : builder.preexistingAccounts) {
            addAccount(seed.username(), seed.password(), seed.roleId(), seed.enabled());
        }
    }

    private void addAccount(String username, String password, String roleId, boolean enabled) {
        int id = nextAccountId.getAndIncrement();
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("Id", Integer.toString(id));
        account.put("UserName", username);
        account.put("Password", password);
        account.put("RoleId", roleId);
        account.put("Enabled", enabled);
        accounts.add(account);
    }

    private HttpsServer createHttpsServer() {
        try {
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()) {
                @Override
                public void configure(HttpsParameters params) {
                    params.setSSLParameters(getSSLContext().getDefaultSSLParameters());
                }
            });
            httpsServer.createContext("/", this::handle);
            httpsServer.start();
            return httpsServer;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Failed to start HTTPS Redfish mock server", e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = readRequestBody(exchange);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        requests.add(new CapturedRequest(method, path, authorization, token, requestBody));

        try {
            maybeDelay();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 500, Map.of("error", "interrupted"));
            return;
        }

        if ("POST".equals(method) && SESSIONS_PATH.equals(path)) {
            handleSessionCreate(exchange, requestBody);
            return;
        }

        if (!isAuthorized(authorization, token)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"redfish-test\"");
            respondJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }

        switch (method) {
            case "GET" -> handleGet(exchange, path);
            case "POST" -> handlePost(exchange, path, requestBody);
            case "PATCH" -> handlePatch(exchange, path, requestBody);
            case "DELETE" -> handleDelete(exchange, path, token);
            default -> respondJson(exchange, 405, Map.of("error", "method not allowed"));
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if (SERVICE_ROOT.equals(path)) {
            respondJson(exchange, 200, serviceRootDocument());
            return;
        }
        if (SESSION_SERVICE_PATH.equals(path)) {
            if (!sessionServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "session service not found"));
                return;
            }
            respondJson(exchange, 200, Map.of(
                    "Id", "SessionService",
                    "Name", "Session Service",
                    "SessionTimeout", 600,
                    "Sessions", Map.of("@odata.id", SESSIONS_PATH)));
            return;
        }
        if (SESSIONS_PATH.equals(path)) {
            if (!sessionServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "sessions not found"));
                return;
            }
            List<Map<String, Object>> members = sessions.stream()
                    .map(session -> Map.<String, Object>of("@odata.id", asString(session.get("@odata.id"))))
                    .toList();
            respondJson(exchange, 200, Map.of("Members", members));
            return;
        }
        if (path.startsWith(SESSIONS_PATH + "/")) {
            if (!sessionServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "session not found"));
                return;
            }
            Map<String, Object> session = findSession(path);
            if (session == null) {
                respondJson(exchange, 404, Map.of("error", "session not found"));
                return;
            }
            respondJson(exchange, 200, session);
            return;
        }
        if (ACCOUNT_SERVICE_PATH.equals(path)) {
            if (!accountServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "account service not found"));
                return;
            }
            respondJson(exchange, 200, Map.of("Accounts", Map.of("@odata.id", ACCOUNTS_PATH)));
            return;
        }
        if (ACCOUNTS_PATH.equals(path)) {
            if (!accountServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "accounts not found"));
                return;
            }
            List<Map<String, Object>> members = accounts.stream()
                    .map(account -> Map.<String, Object>of("@odata.id", ACCOUNTS_PATH + "/" + account.get("Id")))
                    .toList();
            respondJson(exchange, 200, Map.of("Members", members));
            return;
        }
        if (path.startsWith(ACCOUNTS_PATH + "/")) {
            if (!accountServiceEnabled) {
                respondJson(exchange, 404, Map.of("error", "account not found"));
                return;
            }
            String accountId = path.substring((ACCOUNTS_PATH + "/").length());
            Map<String, Object> account = findAccount(accountId);
            if (account == null) {
                respondJson(exchange, 404, Map.of("error", "account not found"));
                return;
            }
            respondJson(exchange, 200, account);
            return;
        }

        Map<String, Object> payload = fixtures.get(path);
        if (payload == null) {
            respondJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        respondJson(exchange, 200, payload);
    }

    private void handlePost(HttpExchange exchange, String path, String requestBody) throws IOException {
        ActionResponse actionResponse = actionResponses.get(path);
        if (actionResponse != null) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json");
            if (actionResponse.locationHeader() != null && !actionResponse.locationHeader().isBlank()) {
                headers.set("Location", actionResponse.locationHeader());
            }
            byte[] body = actionResponse.body() == null
                    ? new byte[0]
                    : objectMapper.writeValueAsBytes(actionResponse.body());
            exchange.sendResponseHeaders(actionResponse.statusCode(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
            return;
        }

        if (!accountServiceEnabled || !ACCOUNTS_PATH.equals(path)) {
            respondJson(exchange, 404, Map.of("error", "not found"));
            return;
        }

        Map<String, Object> payload = parseJson(requestBody);
        addAccount(
                asString(payload.get("UserName")),
                asString(payload.get("Password")),
                Optional.ofNullable(asString(payload.get("RoleId"))).orElse("Administrator"),
                parseEnabled(payload.get("Enabled")));
        respondJson(exchange, 201, accounts.getLast());
    }

    private void handlePatch(HttpExchange exchange, String path, String requestBody) throws IOException {
        if (!accountServiceEnabled || !path.startsWith(ACCOUNTS_PATH + "/")) {
            respondJson(exchange, 404, Map.of("error", "not found"));
            return;
        }

        String accountId = path.substring((ACCOUNTS_PATH + "/").length());
        Map<String, Object> account = findAccount(accountId);
        if (account == null) {
            respondJson(exchange, 404, Map.of("error", "account not found"));
            return;
        }

        Map<String, Object> payload = parseJson(requestBody);
        account.putAll(payload);
        respondJson(exchange, 200, account);
    }

    private void handleDelete(HttpExchange exchange, String path, String token) throws IOException {
        if (!path.startsWith(SESSIONS_PATH + "/")) {
            respondJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        sessions.removeIf(session -> Objects.equals(path, session.get("@odata.id"))
                || Objects.equals(token, session.get("Token")));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void handleSessionCreate(HttpExchange exchange, String requestBody) throws IOException {
        if (!sessionServiceEnabled) {
            respondJson(exchange, 404, Map.of("error", "session service not found"));
            return;
        }

        Map<String, Object> payload = parseJson(requestBody);
        String username = asString(payload.get("UserName"));
        String password = asString(payload.get("Password"));
        if (!Objects.equals(username, bootstrapUsername) || !Objects.equals(password, bootstrapPassword)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"redfish-test\"");
            respondJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }

        int sessionId = nextSessionId.getAndIncrement();
        String sessionUri = SESSIONS_PATH + "/" + sessionId;
        String token = "session-token-" + sessionId;
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("Id", Integer.toString(sessionId));
        session.put("Name", "Session " + sessionId);
        session.put("@odata.id", sessionUri);
        session.put("UserName", username);
        session.put("Token", token);
        sessions.add(session);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Location", sessionUri);
        headers.set("X-Auth-Token", token);
        byte[] body = objectMapper.writeValueAsBytes(Map.of("@odata.id", sessionUri, "Id", Integer.toString(sessionId)));
        exchange.sendResponseHeaders(201, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private Map<String, Object> parseJson(String requestBody) throws IOException {
        if (requestBody == null || requestBody.isBlank()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(requestBody, MAP_TYPE);
    }

    private Map<String, Object> findAccount(String accountId) {
        return accounts.stream()
                .filter(account -> Objects.equals(accountId, account.get("Id")))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findSession(String sessionUri) {
        return sessions.stream()
                .filter(session -> Objects.equals(sessionUri, session.get("@odata.id")))
                .findFirst()
                .orElse(null);
    }

    private void maybeDelay() throws InterruptedException {
        if (!responseDelay.isZero() && !responseDelay.isNegative()) {
            Thread.sleep(responseDelay.toMillis());
        }
    }

    private static boolean parseEnabled(Object rawValue) {
        return !(rawValue instanceof Boolean boolValue) || boolValue;
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean isAuthorized(String authorization, String token) {
        if (Objects.equals(authorization, expectedAuthorization)) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        return sessions.stream().anyMatch(session -> Objects.equals(token, session.get("Token")));
    }

    private void respondJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(payload);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String asString(Object rawValue) {
        return rawValue instanceof String stringValue ? stringValue : null;
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> serviceRootDocument() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Systems", Map.of("@odata.id", SERVICE_ROOT + "/Systems"));
        payload.put("Managers", Map.of("@odata.id", SERVICE_ROOT + "/Managers"));
        payload.put("Chassis", Map.of("@odata.id", SERVICE_ROOT + "/Chassis"));
        if (accountServiceEnabled) {
            payload.put("AccountService", Map.of("@odata.id", ACCOUNT_SERVICE_PATH));
        }
        if (sessionServiceEnabled) {
            payload.put("SessionService", Map.of("@odata.id", SESSION_SERVICE_PATH));
        }
        return payload;
    }

    private static Map<String, Map<String, Object>> loadFixtureBundle(String fixtureName, ObjectMapper objectMapper) {
        Path path = resolveRepoPath("satellite", "pkg", "redfish", "testdata", "vendor-fixtures", fixtureName + ".json");
        try {
            return objectMapper.readValue(path.toFile(), FIXTURE_BUNDLE_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Redfish fixture bundle " + path, e);
        }
    }

    private static Path resolveRepoPath(String... parts) {
        List<Path> candidates = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        candidates.add(cwd.resolve(Path.of("", parts)).normalize());
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve(Path.of("", parts)).normalize());
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Unable to resolve repository asset " + Path.of("", parts));
    }

    private static SSLContext serverSslContext() throws IOException, GeneralSecurityException {
        Path certificatePath = resolveRepoPath("certs", "server.pem");
        Path privateKeyPath = resolveRepoPath("certs", "server-pkcs8.key");

        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Certificate certificate;
        try (InputStream stream = Files.newInputStream(certificatePath)) {
            certificate = certificateFactory.generateCertificate(stream);
        }

        PrivateKey privateKey = loadPrivateKey(privateKeyPath);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, KEYSTORE_PASSWORD);
        keyStore.setKeyEntry("server", privateKey, KEYSTORE_PASSWORD, new Certificate[]{certificate});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return sslContext;
    }

    private static PrivateKey loadPrivateKey(Path privateKeyPath) throws IOException, GeneralSecurityException {
        String pem = Files.readString(privateKeyPath, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    public record CapturedRequest(String method, String path, String authorization, String token, String body) {
    }

    public record ActionResponse(int statusCode, String locationHeader, Map<String, Object> body) {
    }

    public static final class Builder {
        private String fixtureName = "openbmc-baseline";
        private String bootstrapUsername = "admin";
        private String bootstrapPassword = "password";
        private boolean accountServiceEnabled = true;
        private boolean sessionServiceEnabled = false;
        private Duration responseDelay = Duration.ZERO;
        private final List<AccountSeed> preexistingAccounts = new ArrayList<>();
        private final Map<String, Map<String, Object>> fixtureOverrides = new LinkedHashMap<>();
        private final Map<String, ActionResponse> actionResponses = new LinkedHashMap<>();

        public Builder withFixture(String fixtureName) {
            this.fixtureName = Objects.requireNonNull(fixtureName, "fixtureName");
            return this;
        }

        public Builder withBootstrapCredentials(String username, String password) {
            this.bootstrapUsername = Objects.requireNonNull(username, "username");
            this.bootstrapPassword = Objects.requireNonNull(password, "password");
            return this;
        }

        public Builder withoutAccountService() {
            this.accountServiceEnabled = false;
            return this;
        }

        public Builder withSessionService() {
            this.sessionServiceEnabled = true;
            return this;
        }

        public Builder withResponseDelay(Duration responseDelay) {
            this.responseDelay = Objects.requireNonNull(responseDelay, "responseDelay");
            return this;
        }

        public Builder withPreexistingAccount(String username, String password, String roleId) {
            preexistingAccounts.add(new AccountSeed(username, password, roleId, true));
            return this;
        }

        public Builder withFixtureOverride(String path, Map<String, Object> payload) {
            fixtureOverrides.put(Objects.requireNonNull(path, "path"),
                    new LinkedHashMap<>(Objects.requireNonNull(payload, "payload")));
            return this;
        }

        public Builder withActionResponse(String path, int statusCode, String locationHeader,
                Map<String, Object> body) {
            actionResponses.put(Objects.requireNonNull(path, "path"),
                    new ActionResponse(statusCode, locationHeader, body));
            return this;
        }

        public RedfishMockServer build() {
            return new RedfishMockServer(this);
        }

        private record AccountSeed(String username, String password, String roleId, boolean enabled) {
        }
    }
}
