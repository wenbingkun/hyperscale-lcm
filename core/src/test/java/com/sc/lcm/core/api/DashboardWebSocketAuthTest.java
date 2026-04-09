package com.sc.lcm.core.api;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DashboardWebSocketAuthTest {

    @TestHTTPResource("ws/dashboard")
    URI dashboardEndpoint;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void clearConnections() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (DashboardWebSocket.sessionsSnapshot() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertEquals(0, DashboardWebSocket.sessionsSnapshot(), "WebSocket sessions should be closed between tests");
    }

    @Test
    void rejectsConnectionsWithoutJwt() throws Exception {
        RecordingListener listener = new RecordingListener();

        WebSocket socket = connect(dashboardEndpoint, listener);

        CloseFrame closeFrame = listener.closed.get(10, TimeUnit.SECONDS);
        assertEquals(1008, closeFrame.statusCode());
        assertTrue(closeFrame.reason().contains("Unauthorized"));
        socket.abort();
    }

    @Test
    void acceptsConnectionsWithQueryParamJwt() throws Exception {
        String token = login("admin", "admin123");
        RecordingListener listener = new RecordingListener();

        URI websocketUri = URI.create(dashboardEndpoint + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        WebSocket socket = connect(websocketUri, listener);

        String firstMessage = listener.firstText.get(10, TimeUnit.SECONDS);
        assertTrue(firstMessage.contains("\"type\":\"CONNECTED\""));
        assertTrue(firstMessage.contains("Welcome to Hyperscale LCM Dashboard"));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        listener.closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    void acceptsConnectionsWithBearerSubprotocol() throws Exception {
        String token = login("operator", "operator123");
        RecordingListener listener = new RecordingListener();

        WebSocket socket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .subprotocols("bearer", token)
                .buildAsync(toWsUri(dashboardEndpoint), listener)
                .join();

        assertEquals("bearer", socket.getSubprotocol());
        String firstMessage = listener.firstText.get(10, TimeUnit.SECONDS);
        assertTrue(firstMessage.contains("\"type\":\"CONNECTED\""));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        listener.closed.get(10, TimeUnit.SECONDS);
    }

    private WebSocket connect(URI endpoint, RecordingListener listener) {
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(toWsUri(endpoint), listener)
                .join();
    }

    private URI toWsUri(URI endpoint) {
        String scheme = endpoint.getScheme().equals("https") ? "wss" : "ws";
        return URI.create(endpoint.toString().replaceFirst("^https?", scheme));
    }

    private String login(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest(username, password, "default"))
                .when()
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
    }

    record CloseFrame(int statusCode, String reason) {
    }

    static class RecordingListener implements WebSocket.Listener {
        final CompletableFuture<String> firstText = new CompletableFuture<>();
        final CompletableFuture<CloseFrame> closed = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            firstText.complete(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(new CloseFrame(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }
    }
}
