package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.api.DashboardWebSocket;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private static final String ALERT_MANAGER_GENERATOR_URL = "hyperscale-lcm://alert-service";

    @Inject
    DashboardWebSocket dashboardWebSocket;

    @Inject
    SatelliteStateCache stateCache;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.alertmanager.enabled", defaultValue = "false")
    boolean alertManagerEnabled;

    @ConfigProperty(name = "lcm.alertmanager.url")
    Optional<String> alertManagerUrl;

    @ConfigProperty(name = "lcm.alertmanager.connect-timeout-ms", defaultValue = "3000")
    long alertManagerConnectTimeoutMs;

    @ConfigProperty(name = "lcm.alertmanager.request-timeout-ms", defaultValue = "5000")
    long alertManagerRequestTimeoutMs;

    /** 已触发的告警（用于去重） */
    private final Map<String, Long> activeAlerts = new ConcurrentHashMap<>();

    /** 告警静默期（毫秒） */
    private static final long ALERT_COOLDOWN_MS = 300_000; // 5 分钟

    private HttpClient alertManagerHttpClient;

    @PostConstruct
    void init() {
        alertManagerHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000L, alertManagerConnectTimeoutMs)))
                .build();
    }

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
        forwardAlertToAlertManager("NodeOffline", "CRITICAL", message, "NodeHealthCheck",
                Map.of("node_id", nodeId));

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
        forwardAlertToAlertManager("JobScheduleFailed", "WARNING", message, "Scheduler",
                Map.of("job_id", jobId));
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
        forwardAlertToAlertManager("GpuOverheat", "WARNING", message, "Telemetry",
                Map.of("node_id", nodeId, "temperature_celsius", String.valueOf(temperature)));
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

    private void forwardAlertToAlertManager(String alertName, String severity, String message, String source,
            Map<String, String> extraLabels) {
        if (!alertManagerEnabled) {
            return;
        }
        if (alertManagerUrl.isEmpty()) {
            log.warn("AlertManager push is enabled but lcm.alertmanager.url is not configured");
            return;
        }

        try {
            String payload = buildAlertManagerPayload(alertName, severity, message, source, extraLabels);
            HttpRequest request = HttpRequest.newBuilder(URI.create(alertManagerUrl.get()))
                    .timeout(Duration.ofMillis(Math.max(1_000L, alertManagerRequestTimeoutMs)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            alertManagerHttpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.error("Failed to push alert {} to AlertManager", alertName, error);
                            return;
                        }
                        if (response.statusCode() / 100 != 2) {
                            log.warn("AlertManager rejected alert {} with status {}", alertName, response.statusCode());
                            return;
                        }
                        log.info("Forwarded alert {} to AlertManager", alertName);
                    });
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            log.error("Failed to build AlertManager request for alert {}", alertName, exception);
        }
    }

    private String buildAlertManagerPayload(String alertName, String severity, String message, String source,
            Map<String, String> extraLabels) throws JsonProcessingException {
        Map<String, String> labels = new HashMap<>();
        labels.put("alertname", alertName);
        labels.put("severity", severity.toLowerCase(Locale.ROOT));
        labels.put("source", source);
        labels.putAll(extraLabels);

        Map<String, Object> alert = new HashMap<>();
        alert.put("labels", labels);
        alert.put("annotations", Map.of(
                "summary", message,
                "description", message));
        alert.put("generatorURL", ALERT_MANAGER_GENERATOR_URL);
        alert.put("startsAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        return objectMapper.writeValueAsString(List.of(alert));
    }
}
