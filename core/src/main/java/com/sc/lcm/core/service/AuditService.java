package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.AuditLog;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

/**
 * 审计服务 (P4-5)
 * 
 * 记录关键操作用于安全审计和合规
 */
@ApplicationScoped
@Slf4j
public class AuditService {

    /**
     * 记录审计事件
     */
    public Uni<Void> logEvent(AuditEventType eventType, String resourceType,
            String resourceId, String actor, String tenantId,
            String details) {
        AuditLog auditLog = new AuditLog(eventType, resourceType, resourceId,
                actor, tenantId, details);

        return Panache.withTransaction(auditLog::persist)
                .invoke(() -> log.info("📋 AUDIT: {} {} {} by {}",
                        eventType, resourceType, resourceId, actor))
                .replaceWithVoid();
    }

    // ============== 便捷方法 ==============

    /**
     * 记录作业提交
     */
    public Uni<Void> logJobSubmitted(String jobId, String actor, String tenantId) {
        return logEvent(AuditEventType.JOB_SUBMITTED, "JOB", jobId, actor, tenantId,
                "{\"action\":\"submitted\"}");
    }

    /**
     * 记录作业完成
     */
    public Uni<Void> logJobCompleted(String jobId, String actor, String tenantId, int exitCode) {
        return logEvent(AuditEventType.JOB_COMPLETED, "JOB", jobId, actor, tenantId,
                String.format("{\"action\":\"completed\",\"exitCode\":%d}", exitCode));
    }

    /**
     * 记录作业失败
     */
    public Uni<Void> logJobFailed(String jobId, String actor, String tenantId, String error) {
        return logEvent(AuditEventType.JOB_FAILED, "JOB", jobId, actor, tenantId,
                String.format("{\"action\":\"failed\",\"error\":\"%s\"}", error));
    }

    /**
     * 记录作业取消
     */
    public Uni<Void> logJobCancelled(String jobId, String actor, String tenantId) {
        return logEvent(AuditEventType.JOB_CANCELLED, "JOB", jobId, actor, tenantId,
                "{\"action\":\"cancelled\"}");
    }

    /**
     * 记录节点注册
     */
    public Uni<Void> logNodeRegistered(String nodeId, String hostname, String ipAddress) {
        return logEvent(AuditEventType.NODE_REGISTERED, "NODE", nodeId, "SYSTEM", null,
                String.format("{\"hostname\":\"%s\",\"ip\":\"%s\"}", hostname, ipAddress));
    }

    /**
     * 记录节点离线
     */
    public Uni<Void> logNodeOffline(String nodeId) {
        return logEvent(AuditEventType.NODE_OFFLINE, "NODE", nodeId, "SYSTEM", null,
                "{\"action\":\"offline\"}");
    }

    /**
     * 记录节点状态变更
     */
    public Uni<Void> logNodeStatusChanged(String nodeId, String oldStatus, String newStatus, String actor) {
        return logEvent(AuditEventType.NODE_STATUS_CHANGED, "NODE", nodeId, actor, null,
                String.format("{\"from\":\"%s\",\"to\":\"%s\"}", oldStatus, newStatus));
    }

    /**
     * 记录配置变更
     */
    public Uni<Void> logConfigChanged(String configKey, String actor, String tenantId, String oldValue,
            String newValue) {
        return logEvent(AuditEventType.CONFIG_CHANGED, "CONFIG", configKey, actor, tenantId,
                String.format("{\"old\":\"%s\",\"new\":\"%s\"}", oldValue, newValue));
    }
}
