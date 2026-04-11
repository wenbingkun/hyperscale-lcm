package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志实体 (P4-5)
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件类型 */
    @Enumerated(EnumType.STRING)
    private AuditEventType eventType;

    /** 资源类型 (JOB, NODE, CONFIG) */
    private String resourceType;

    /** 资源 ID */
    private String resourceId;

    /** 操作者 (用户 ID 或系统) */
    private String actor;

    /** 租户 ID */
    private String tenantId;

    /** 事件详情 (JSON) */
    @Column(columnDefinition = "TEXT")
    private String details;

    /** IP 地址 */
    private String ipAddress;

    /** 时间戳 */
    @CreationTimestamp
    private LocalDateTime createdAt;

    // ============== 构造器 ==============

    public AuditLog(AuditEventType eventType, String resourceType, String resourceId,
            String actor, String tenantId, String details) {
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.actor = actor;
        this.tenantId = tenantId;
        this.details = details;
    }

    // ============== 响应式查询 ==============

    public static Uni<List<AuditLog>> findByResourceId(String resourceId) {
        return list("resourceId", resourceId);
    }

    public static Uni<List<AuditLog>> findByTenant(String tenantId) {
        return list("tenantId = ?1 order by createdAt desc", tenantId);
    }

    public static Uni<List<AuditLog>> findRecent(int limit) {
        return find("order by createdAt desc").page(0, limit).list();
    }

    // ============== 事件类型枚举 ==============

    public enum AuditEventType {
        // 作业相关
        JOB_SUBMITTED,
        JOB_SCHEDULED,
        JOB_COMPLETED,
        JOB_FAILED,
        JOB_CANCELLED,

        // 节点相关
        NODE_REGISTERED,
        NODE_OFFLINE,
        NODE_STATUS_CHANGED,

        // 配置相关
        CONFIG_CHANGED,

        // 安全相关
        AUTH_SUCCESS,
        AUTH_FAILURE,
        ACCESS_DENIED,

        // BMC / Redfish 相关
        BMC_CLAIM,
        BMC_ROTATE_CREDENTIALS,
        BMC_POWER_ACTION
    }
}
