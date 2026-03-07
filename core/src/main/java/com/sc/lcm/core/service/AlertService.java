package com.sc.lcm.core.service;

import com.sc.lcm.core.api.DashboardWebSocket;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警服务 (P3-3)
 * 
 * 功能：
 * - 规则引擎：评估告警条件
 * - 通知路由：推送到 Dashboard / Webhook / Email
 * - 告警聚合：避免告警风暴
 */
@ApplicationScoped
@Slf4j
public class AlertService {

    @Inject
    DashboardWebSocket dashboardWebSocket;

    @Inject
    SatelliteStateCache stateCache;

    /** 已触发的告警（用于去重） */
    private final Map<String, Long> activeAlerts = new ConcurrentHashMap<>();

    /** 告警静默期（毫秒） */
    private static final long ALERT_COOLDOWN_MS = 300_000; // 5 分钟

    /**
     * 定期检查节点状态并触发告警
     */
    @Scheduled(every = "30s")
    void checkNodeHealth() {
        log.debug("🔍 Running node health check...");
        // 遍历所有节点检查心跳超时
        // 当前为简化实现，实际应从数据库或 Redis 获取节点列表
    }

    /**
     * 触发节点离线告警
     */
    public void raiseNodeOfflineAlert(String nodeId) {
        String alertKey = "NODE_OFFLINE:" + nodeId;

        if (shouldSuppress(alertKey)) {
            log.debug("Suppressing duplicate alert: {}", alertKey);
            return;
        }

        String message = String.format("节点 %s 已离线超过 2 分钟", nodeId);

        // 推送到 Dashboard
        dashboardWebSocket.broadcastAlert("CRITICAL", message, "NodeHealthCheck");

        // 记录活跃告警
        activeAlerts.put(alertKey, System.currentTimeMillis());

        log.warn("🚨 ALERT: {}", message);

        // 发送 Webhook 通知
        // 发送 Email 通知
    }

    /**
     * 触发调度失败告警
     */
    public void raiseScheduleFailedAlert(String jobId, String reason) {
        String alertKey = "SCHEDULE_FAILED:" + jobId;

        if (shouldSuppress(alertKey)) {
            return;
        }

        String message = String.format("作业 %s 调度失败: %s", jobId, reason);
        dashboardWebSocket.broadcastAlert("WARNING", message, "Scheduler");
        activeAlerts.put(alertKey, System.currentTimeMillis());

        log.warn("⚠️ ALERT: {}", message);
    }

    /**
     * 触发 GPU 过热告警
     */
    public void raiseGpuOverheatAlert(String nodeId, int temperature) {
        String alertKey = "GPU_OVERHEAT:" + nodeId;

        if (shouldSuppress(alertKey)) {
            return;
        }

        String message = String.format("节点 %s GPU 温度过高: %d°C", nodeId, temperature);
        dashboardWebSocket.broadcastAlert("WARNING", message, "Telemetry");
        activeAlerts.put(alertKey, System.currentTimeMillis());

        log.warn("🔥 ALERT: {}", message);
    }

    /**
     * 清除告警
     */
    public void clearAlert(String alertKey) {
        activeAlerts.remove(alertKey);
        log.info("✅ Alert cleared: {}", alertKey);
    }

    /**
     * 清除所有活跃告警（测试支持）
     */
    void clearAlerts() {
        activeAlerts.clear();
    }

    /**
     * 检查是否应抑制告警（去重）
     */
    private boolean shouldSuppress(String alertKey) {
        Long lastAlertTime = activeAlerts.get(alertKey);
        if (lastAlertTime == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastAlertTime) < ALERT_COOLDOWN_MS;
    }

    /**
     * 获取活跃告警数量
     */
    public int getActiveAlertCount() {
        // 清理过期告警
        long now = System.currentTimeMillis();
        activeAlerts.entrySet().removeIf(entry -> (now - entry.getValue()) > ALERT_COOLDOWN_MS * 2);
        return activeAlerts.size();
    }
}
