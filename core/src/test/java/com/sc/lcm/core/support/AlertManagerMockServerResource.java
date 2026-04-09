package com.sc.lcm.core.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AlertManagerMockServerResource implements QuarkusTestResourceLifecycleManager {

    private static final BlockingQueue<CapturedRequest> REQUESTS = new LinkedBlockingQueue<>();

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        REQUESTS.clear();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start AlertManager mock server", e);
        }

        server.createContext("/api/v2/alerts", this::handleAlertPush);
        server.start();

        return Map.of(
                "lcm.alertmanager.enabled", "true",
                "lcm.alertmanager.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/alerts");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        REQUESTS.clear();
    }

    public static void clearRequests() {
        REQUESTS.clear();
    }

    public static CapturedRequest awaitRequest(Duration timeout) throws InterruptedException {
        return REQUESTS.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleAlertPush(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUESTS.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));

        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(202, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    public record CapturedRequest(String method, String path, String contentType, String body) {
    }
}
