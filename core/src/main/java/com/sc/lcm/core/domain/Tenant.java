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
 * 租户实体 - 多租户资源隔离
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant extends PanacheEntityBase {

    @Id
    private String id;

    /** 租户名称 */
    @Column(nullable = false)
    private String name;

    /** 描述 */
    private String description;

    /** 状态: ACTIVE, SUSPENDED, DELETED */
    @Enumerated(EnumType.STRING)
    private TenantStatus status = TenantStatus.ACTIVE;

    /** 创建时间 */
    private LocalDateTime createdAt = LocalDateTime.now();

    // ============== 资源配额 ==============

    /** CPU 核心配额 */
    private int cpuQuota = 1000;

    /** 内存配额 (GB) */
    private long memoryQuotaGb = 4096;

    /** GPU 配额 */
    private int gpuQuota = 100;

    /** 最大并发作业数 */
    private int maxConcurrentJobs = 50;

    // ============== 资源使用统计 ==============

    /** 已使用 CPU */
    private int cpuUsed = 0;

    /** 已使用内存 */
    private long memoryUsedGb = 0;

    /** 已使用 GPU */
    private int gpuUsed = 0;

    /** 当前运行作业数 */
    private int runningJobs = 0;

    public enum TenantStatus {
        ACTIVE,
        SUSPENDED,
        DELETED
    }

    public Tenant(String id, String name) {
        this.id = id;
        this.name = name;
    }

    // ============== 便捷方法 ==============

    public boolean hasAvailableCpu(int required) {
        return cpuUsed + required <= cpuQuota;
    }

    public boolean hasAvailableMemory(long requiredGb) {
        return memoryUsedGb + requiredGb <= memoryQuotaGb;
    }

    public boolean hasAvailableGpu(int required) {
        return gpuUsed + required <= gpuQuota;
    }

    public boolean canRunMoreJobs() {
        return runningJobs < maxConcurrentJobs;
    }

    public double getCpuUtilization() {
        return cpuQuota > 0 ? (double) cpuUsed / cpuQuota * 100 : 0;
    }

    public double getGpuUtilization() {
        return gpuQuota > 0 ? (double) gpuUsed / gpuQuota * 100 : 0;
    }

    // ============== 查询方法 ==============

    public static Uni<List<Tenant>> findActive() {
        return list("status", TenantStatus.ACTIVE);
    }

    public static Uni<Tenant> findByIdUni(String id) {
        return find("id", id).firstResult();
    }
}
