package com.sc.lcm.core.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 作业实体 - 支持持久化 + GPU 拓扑需求 + Timefold 调度
 */
@Entity
@Table(name = "job")
@PlanningEntity
@Getter
@Setter
@NoArgsConstructor
public class Job extends PanacheEntityBase {

    @Id
    @PlanningId
    private String id;

    private String name;
    private String description;

    // 基础资源需求
    private int requiredCpuCores;
    private long requiredMemoryGb;
    private int requiredGpuCount;
    private String requiredGpuModel;

    // GPU 拓扑需求 (P2-3)
    private boolean requiresNvlink;
    private int minNvlinkBandwidthGbps;

    // 作业状态
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    // 分配的节点 ID (持久化用)
    private String assignedNodeId;

    // Timefold 运行时使用 (不持久化)
    @Transient
    @PlanningVariable(valueRangeProviderRefs = "nodeRange")
    private Node assignedNode;

    // 多租户支持 (P4-4)
    private String tenantId;

    // 多集群支持 (P5-5): 调度到指定 cluster 的 Satellite
    private String clusterId = "default";

    // 优先级队列 (P5-3): 1-10, 10最高
    private int priority = 5;

    // 是否可被抢占
    private boolean preemptible = true;

    // 节点亲和性 (P5-4): e.g., "gpu=a100,zone=us-west"
    private String nodeSelector;

    // 执行策略 (Stage 6): 决定 Core 下发到 Satellite 的命令类型
    @Enumerated(EnumType.STRING)
    private ExecutionType executionType = ExecutionType.DOCKER;

    @Column(columnDefinition = "TEXT")
    private String executionPayload;

    // 时间戳
    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime scheduledAt;
    private LocalDateTime completedAt;

    // 执行结果
    private Integer exitCode;
    private String errorMessage;

    // ============== 构造器 ==============

    public Job(String id, int requiredCpuCores, long requiredMemoryGb, int requiredGpuCount, String requiredGpuModel) {
        this.id = id;
        this.requiredCpuCores = requiredCpuCores;
        this.requiredMemoryGb = requiredMemoryGb;
        this.requiredGpuCount = requiredGpuCount;
        this.requiredGpuModel = requiredGpuModel;
        this.status = JobStatus.PENDING;
        this.clusterId = "default";
    }

    public Job(String id, int requiredCpuCores, long requiredMemoryGb,
            int requiredGpuCount, String requiredGpuModel,
            boolean requiresNvlink, int minNvlinkBandwidthGbps) {
        this(id, requiredCpuCores, requiredMemoryGb, requiredGpuCount, requiredGpuModel);
        this.requiresNvlink = requiresNvlink;
        this.minNvlinkBandwidthGbps = minNvlinkBandwidthGbps;
    }

    // ============== 响应式查询方法 ==============

    public static Uni<Job> findByIdReactive(String id) {
        return findById(id);
    }

    public static Uni<List<Job>> findByStatus(JobStatus status) {
        return list("status", status);
    }

    public static Uni<List<Job>> findByTenant(String tenantId) {
        return list("tenantId", tenantId);
    }

    public static Uni<List<Job>> findPending() {
        return list("status", JobStatus.PENDING);
    }

    public static Uni<Long> countByStatus(JobStatus status) {
        return count("status", status);
    }

    // ============== 状态枚举 ==============

    public enum JobStatus {
        PENDING, // 待调度
        SCHEDULED, // 已调度
        RUNNING, // 运行中
        COMPLETED, // 已完成
        FAILED, // 失败
        CANCELLED // 已取消
    }

    public enum ExecutionType {
        DOCKER,
        SHELL,
        ANSIBLE,
        SSH
    }
}
