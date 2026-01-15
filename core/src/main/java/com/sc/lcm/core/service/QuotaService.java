package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.ResourceQuota;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

/**
 * 配额服务 (P5-2)
 * 
 * 检查租户资源使用是否超出配额
 */
@ApplicationScoped
@Slf4j
public class QuotaService {

    /**
     * 检查作业提交是否超出配额
     * 
     * @return null 表示通过，否则返回拒绝原因
     */
    public Uni<String> checkJobSubmission(Job job) {
        String tenantId = job.getTenantId();
        if (tenantId == null) {
            tenantId = "default";
        }

        final String finalTenantId = tenantId;

        return ResourceQuota.getOrDefault(finalTenantId)
                .chain(quota -> {
                    if (!quota.isEnabled()) {
                        return Uni.createFrom().nullItem(); // 配额未启用，允许
                    }

                    // 检查并发作业数
                    return Job.countByStatus(JobStatus.RUNNING)
                            .chain(runningCount -> {
                                if (runningCount >= quota.getMaxConcurrentJobs()) {
                                    log.warn("❌ Quota exceeded for tenant {}: max concurrent jobs", finalTenantId);
                                    return Uni.createFrom().item(
                                            String.format("超出并发作业限制: %d/%d",
                                                    runningCount, quota.getMaxConcurrentJobs()));
                                }

                                // 检查 GPU 配额
                                if (job.getRequiredGpuCount() > quota.getMaxGpuCount()) {
                                    log.warn("❌ Quota exceeded for tenant {}: GPU count", finalTenantId);
                                    return Uni.createFrom().item(
                                            String.format("超出 GPU 配额: 请求 %d, 限制 %d",
                                                    job.getRequiredGpuCount(), quota.getMaxGpuCount()));
                                }

                                // 检查 CPU 配额
                                if (job.getRequiredCpuCores() > quota.getMaxCpuCores()) {
                                    log.warn("❌ Quota exceeded for tenant {}: CPU cores", finalTenantId);
                                    return Uni.createFrom().item(
                                            String.format("超出 CPU 配额: 请求 %d, 限制 %d",
                                                    job.getRequiredCpuCores(), quota.getMaxCpuCores()));
                                }

                                // 检查内存配额
                                if (job.getRequiredMemoryGb() > quota.getMaxMemoryGb()) {
                                    log.warn("❌ Quota exceeded for tenant {}: memory", finalTenantId);
                                    return Uni.createFrom().item(
                                            String.format("超出内存配额: 请求 %d GB, 限制 %d GB",
                                                    job.getRequiredMemoryGb(), quota.getMaxMemoryGb()));
                                }

                                log.debug("✅ Quota check passed for tenant {}", finalTenantId);
                                return Uni.createFrom().nullItem();
                            });
                });
    }

    /**
     * 获取租户配额使用情况
     */
    public Uni<QuotaUsage> getQuotaUsage(String tenantId) {
        return ResourceQuota.getOrDefault(tenantId)
                .chain(quota -> Job.findByStatus(JobStatus.RUNNING)
                        .onItem().transform(runningJobs -> {
                            int usedCpu = runningJobs.stream()
                                    .filter(j -> tenantId.equals(j.getTenantId()))
                                    .mapToInt(Job::getRequiredCpuCores)
                                    .sum();
                            int usedGpu = runningJobs.stream()
                                    .filter(j -> tenantId.equals(j.getTenantId()))
                                    .mapToInt(Job::getRequiredGpuCount)
                                    .sum();
                            long usedMemory = runningJobs.stream()
                                    .filter(j -> tenantId.equals(j.getTenantId()))
                                    .mapToLong(Job::getRequiredMemoryGb)
                                    .sum();
                            int usedJobs = (int) runningJobs.stream()
                                    .filter(j -> tenantId.equals(j.getTenantId()))
                                    .count();

                            return new QuotaUsage(
                                    quota.getMaxCpuCores(), usedCpu,
                                    quota.getMaxMemoryGb(), usedMemory,
                                    quota.getMaxGpuCount(), usedGpu,
                                    quota.getMaxConcurrentJobs(), usedJobs);
                        }));
    }

    public record QuotaUsage(
            int maxCpuCores, int usedCpuCores,
            long maxMemoryGb, long usedMemoryGb,
            int maxGpuCount, int usedGpuCount,
            int maxConcurrentJobs, int usedConcurrentJobs) {
    }
}
