package com.sc.lcm.core.api;

import com.sc.lcm.core.api.WsEvent.*;
import com.sc.lcm.core.service.SatelliteStateCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点 - 实时推送 Dashboard 数据
 */
@ServerEndpoint("/ws/dashboard")
@ApplicationScoped
@Slf4j
public class DashboardWebSocket {

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    SatelliteStateCache stateCache;

    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("🌐 Dashboard client connected: {} (Total: {})", sessionId, sessions.size());

        WsEvent welcome = WsEvent.of(EventType.CONNECTED, new ConnectedPayload("Welcome to Hyperscale LCM Dashboard"));
        sendToSession(session, welcome.toJson());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());
        log.info("🔌 Dashboard client disconnected: {} (Total: {})", session.getId(), sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("❌ WebSocket error for session {}: {}", session.getId(), throwable.getMessage());
        sessions.remove(session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.debug("📩 Received message from {}: {}", session.getId(), message);

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
}
