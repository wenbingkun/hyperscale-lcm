package com.sc.lcm.core.api;

import com.sc.lcm.core.service.SatelliteStateCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点 - 实时推送 Dashboard 数据
 * 
 * 功能：
 * - 节点状态变更推送
 * - 调度事件推送
 * - 告警通知推送
 */
@ServerEndpoint("/ws/dashboard")
@ApplicationScoped
@Slf4j
public class DashboardWebSocket {

    /** 已连接的 Dashboard 客户端 */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    SatelliteStateCache stateCache;

    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("🌐 Dashboard client connected: {} (Total: {})", sessionId, sessions.size());

        // 发送欢迎消息
        sendToSession(session, "{\"type\":\"CONNECTED\",\"message\":\"Welcome to Hyperscale LCM Dashboard\"}");
    }

    @OnClose
    public void onClose(Session session) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("🔌 Dashboard client disconnected: {} (Total: {})", sessionId, sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("❌ WebSocket error for session {}: {}", session.getId(), throwable.getMessage());
        sessions.remove(session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.debug("📩 Received message from {}: {}", session.getId(), message);

        // 处理客户端请求
        if ("PING".equals(message)) {
            sendToSession(session, "{\"type\":\"PONG\"}");
        } else if ("GET_STATUS".equals(message)) {
            // 返回当前在线节点数
            int onlineCount = stateCache.getOnlineCount();
            sendToSession(session, String.format("{\"type\":\"STATUS\",\"onlineNodes\":%d}", onlineCount));
        }
    }

    /**
     * 广播消息给所有连接的客户端
     */
    public void broadcast(String message) {
        sessions.values().forEach(session -> sendToSession(session, message));
    }

    /**
     * 广播节点状态变更
     */
    public void broadcastNodeStatus(String nodeId, String status) {
        String json = String.format(
                "{\"type\":\"NODE_STATUS\",\"nodeId\":\"%s\",\"status\":\"%s\",\"timestamp\":%d}",
                nodeId, status, System.currentTimeMillis());
        broadcast(json);
    }

    /**
     * 广播调度事件
     */
    public void broadcastScheduleEvent(String jobId, String nodeId, String action) {
        String json = String.format(
                "{\"type\":\"SCHEDULE_EVENT\",\"jobId\":\"%s\",\"nodeId\":\"%s\",\"action\":\"%s\",\"timestamp\":%d}",
                jobId, nodeId, action, System.currentTimeMillis());
        broadcast(json);
    }

    /**
     * 广播告警
     */
    public void broadcastAlert(String severity, String message) {
        String json = String.format(
                "{\"type\":\"ALERT\",\"severity\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
                severity, message, System.currentTimeMillis());
        broadcast(json);
    }

    private void sendToSession(Session session, String message) {
        try {
            if (session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            log.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return sessions.size();
    }
}
