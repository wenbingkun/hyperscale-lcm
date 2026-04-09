package com.sc.lcm.core.api;

import com.sc.lcm.core.api.WsEvent.*;
import com.sc.lcm.core.service.SatelliteStateCache;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点 - 实时推送 Dashboard 数据
 */
@ServerEndpoint(value = "/ws/dashboard", subprotocols = { "bearer" }, configurator = DashboardWebSocket.HandshakeConfigurator.class)
@ApplicationScoped
@Slf4j
public class DashboardWebSocket {

    static final String WS_TOKEN_QUERY_PARAM = "token";
    static final String AUTHORIZATION_HEADER = "Authorization";
    static final String HANDSHAKE_HEADERS_KEY = "handshake.headers";

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    SatelliteStateCache stateCache;

    @Inject
    JWTParser jwtParser;

    @OnOpen
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        JsonWebToken principal = authenticate(extractToken(session, endpointConfig));
        if (principal == null) {
            closeUnauthorized(session);
            return;
        }

        String sessionId = session.getId();
        sessions.put(sessionId, session);
        session.getUserProperties().put("principal", principal.getName());
        log.info("Dashboard client connected: {} user={} (Total: {})", sessionId, principal.getName(), sessions.size());

        WsEvent welcome = WsEvent.of(EventType.CONNECTED, new ConnectedPayload("Welcome to Hyperscale LCM Dashboard"));
        sendToSession(session, welcome.toJson());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());
        log.info("Dashboard client disconnected: {} (Total: {})", session.getId(), sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        String sessionId = session != null ? session.getId() : "unknown";
        log.error("WebSocket error for session {}: {}", sessionId, throwable.getMessage());
        if (session != null) {
            sessions.remove(sessionId);
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.debug("Received message from {}: {}", session.getId(), message);

        if ("PING".equals(message)) {
            sendToSession(session, WsEvent.of(EventType.PONG, null).toJson());
        } else if ("GET_STATUS".equals(message)) {
            int onlineCount = stateCache.getOnlineCount();
            StatusPayload payload = StatusPayload.builder()
                    .onlineNodes(onlineCount)
                    .build();
            sendToSession(session, WsEvent.of(EventType.STATUS, payload).toJson());
        }
    }

    public void broadcast(WsEvent event) {
        String json = event.toJson();
        sessions.values().forEach(session -> sendToSession(session, json));
    }

    public void broadcastNodeStatus(String nodeId, String hostname, String status) {
        broadcast(WsEvent.of(EventType.NODE_STATUS, new NodeStatusPayload(nodeId, hostname, status)));
    }

    public void broadcastHeartbeat(HeartbeatPayload payload) {
        broadcast(WsEvent.of(EventType.HEARTBEAT_UPDATE, payload));
    }

    public void broadcastScheduleEvent(String jobId, String nodeId, String action) {
        broadcast(WsEvent.of(EventType.SCHEDULE_EVENT, new SchedulePayload(jobId, nodeId, action)));
    }

    public void broadcastJobStatus(String jobId, String jobName, String status, String assignedNodeId,
            Integer exitCode) {
        broadcast(WsEvent.of(EventType.JOB_STATUS,
                new JobStatusPayload(jobId, jobName, status, assignedNodeId, exitCode)));
    }

    public void broadcastDiscovery(String ipAddress, String macAddress, String discoveryMethod) {
        broadcast(WsEvent.of(EventType.DISCOVERY_EVENT, new DiscoveryPayload(ipAddress, macAddress, discoveryMethod)));
    }

    public void broadcastAlert(String severity, String message, String source) {
        broadcast(WsEvent.of(EventType.ALERT, new AlertPayload(severity, message, source)));
    }

    private void sendToSession(Session session, String message) {
        if (session.isOpen()) {
            session.getAsyncRemote().sendText(message, result -> {
                if (!result.isOK()) {
                    log.error("Failed to send message to session {}: {}",
                            session.getId(), result.getException().getMessage());
                }
            });
        }
    }

    public int getConnectionCount() {
        return sessions.size();
    }

    static int sessionsSnapshot() {
        return sessions.size();
    }

    static String extractProtocolToken(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (String headerValue : headerValues) {
            if (headerValue == null || headerValue.isBlank()) {
                continue;
            }
            for (String candidate : headerValue.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                }
            }
        }

        for (int i = 0; i < parts.size(); i++) {
            String candidate = stripBearerPrefix(parts.get(i));
            if (looksLikeJwt(candidate)) {
                return candidate;
            }
            if ("bearer".equalsIgnoreCase(parts.get(i)) && i + 1 < parts.size()) {
                candidate = stripBearerPrefix(parts.get(i + 1));
                if (looksLikeJwt(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private JsonWebToken authenticate(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Rejecting dashboard websocket connection without JWT");
            return null;
        }

        try {
            return jwtParser.parse(token);
        } catch (ParseException e) {
            log.warn("Rejecting dashboard websocket connection with invalid JWT: {}", e.getMessage());
            return null;
        }
    }

    private String extractToken(Session session, EndpointConfig endpointConfig) {
        String token = firstValue(session.getRequestParameterMap().get(WS_TOKEN_QUERY_PARAM));
        token = stripBearerPrefix(token);
        if (looksLikeJwt(token)) {
            return token;
        }

        Map<String, List<String>> handshakeHeaders = handshakeHeaders(endpointConfig);

        token = stripBearerPrefix(firstValue(handshakeHeaders.get(AUTHORIZATION_HEADER)));
        if (looksLikeJwt(token)) {
            return token;
        }

        return extractProtocolToken(handshakeHeaders.get(HandshakeRequest.SEC_WEBSOCKET_PROTOCOL));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> handshakeHeaders(EndpointConfig endpointConfig) {
        Object value = endpointConfig.getUserProperties().get(HANDSHAKE_HEADERS_KEY);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, List<String>>) map;
        }
        return Map.of();
    }

    private void closeUnauthorized(Session session) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Unauthorized websocket connection"));
        } catch (IOException e) {
            log.debug("Failed to close unauthorized dashboard websocket session {}", session.getId(), e);
        }
    }

    private static String firstValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static String stripBearerPrefix(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return token.substring(7).trim();
        }
        return token.trim();
    }

    private static boolean looksLikeJwt(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        return firstDot > 0 && lastDot > firstDot && lastDot < token.length() - 1;
    }

    public static class HandshakeConfigurator extends ServerEndpointConfig.Configurator {

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            sec.getUserProperties().put(HANDSHAKE_HEADERS_KEY, request.getHeaders());
        }
    }
}
