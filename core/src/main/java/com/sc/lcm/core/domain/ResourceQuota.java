package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 资源配额实体 (P5-2)
 * 
 * 按租户限制资源使用
 */
@Entity
@Table(name = "resource_quota")
@Getter
@Setter
@NoArgsConstructor
public class ResourceQuota extends PanacheEntityBase {

    @Id
    private String tenantId;

    /** 最大 CPU 核数 */
    private Integer maxCpuCores;

    /** 最大内存 (GB) */
    private Long maxMemoryGb;

    /** 最大 GPU 数量 */
    private Integer maxGpuCount;

    /** 最大并发作业数 */
    private Integer maxConcurrentJobs;

    /** 配额是否启用 */
    private boolean enabled = true;

    // ============== 响应式查询 ==============

    public static Uni<ResourceQuota> findByTenant(String tenantId) {
        return findById(tenantId);
    }

    public static Uni<ResourceQuota> getOrDefault(String tenantId) {
        return findByTenant(tenantId)
                .onItem().ifNull().continueWith(() -> {
                    // 返回默认配额
                    ResourceQuota defaultQuota = new ResourceQuota();
                    defaultQuota.setTenantId(tenantId);
                    defaultQuota.setMaxCpuCores(1000);
                    defaultQuota.setMaxMemoryGb(4096L);
                    defaultQuota.setMaxGpuCount(100);
                    defaultQuota.setMaxConcurrentJobs(50);
                    defaultQuota.setEnabled(true);
                    return defaultQuota;
                });
    }
}
