package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网络扫描任务记录
 */
@Entity
@Table(name = "scan_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ScanJob extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** 扫描目标 (CIDR 或 IP 范围) */
    @Column(nullable = false)
    private String target;

    /** 扫描端口列表 (逗号分隔) */
    private String ports = "22,8080,9000,623";

    /** 扫描状态 */
    @Enumerated(EnumType.STRING)
    private ScanStatus status = ScanStatus.PENDING;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 扫描的 IP 总数 */
    private int totalIps = 0;

    /** 已扫描数量 */
    private int scannedCount = 0;

    /** 发现的设备数量 */
    private int discoveredCount = 0;

    /** 扫描进度百分比 */
    private int progressPercent = 0;

    /** 错误信息 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** 创建人 */
    private String createdBy;

    /** 所属租户 */
    private String tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum ScanStatus {
        PENDING, // 等待执行
        RUNNING, // 执行中
        COMPLETED, // 完成
        FAILED, // 失败
        CANCELLED // 已取消
    }

    public ScanJob(String target, String createdBy) {
        this.target = target;
        this.createdBy = createdBy;
    }

    // ============== 查询方法 ==============

    public static Uni<List<ScanJob>> findRecent(int limit) {
        return find("ORDER BY createdAt DESC").page(0, limit).list();
    }

    public static Uni<List<ScanJob>> findByStatus(ScanStatus status) {
        return list("status", status);
    }

    public static Uni<ScanJob> findRunning() {
        return find("status", ScanStatus.RUNNING).firstResult();
    }
}
