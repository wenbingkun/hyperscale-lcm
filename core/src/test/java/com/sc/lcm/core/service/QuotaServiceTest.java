package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.ResourceQuota;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class QuotaServiceTest {

    @Inject
    QuotaService quotaService;

    @BeforeEach
    void resetPersistence() throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> Job.deleteAll()
                .chain(() -> ResourceQuota.deleteAll())
                .replaceWithVoid()));
    }

    @Test
    void checkJobSubmissionRejectsWhenConcurrentJobLimitIsExceeded() throws Throwable {
        ResourceQuota quota = quota("tenant-a", true, 64, 512L, 8, 1);
        Job runningJob = runningJob("job-running", "tenant-a", 8, 64, 1);
        Job candidate = pendingJob("job-candidate", "tenant-a", 4, 32, 1);

        persistQuotaAndJobs(quota, runningJob);

        String result = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> quotaService.checkJobSubmission(candidate)));

        assertEquals("超出并发作业限制: 1/1", result);
    }

    @Test
    void checkJobSubmissionAllowsRequestsWhenQuotaIsDisabled() throws Throwable {
        ResourceQuota quota = quota("tenant-a", false, 1, 1L, 1, 1);
        Job runningJob = runningJob("job-running", "tenant-a", 8, 64, 4);
        Job candidate = pendingJob("job-candidate", "tenant-a", 128, 4096, 16);

        persistQuotaAndJobs(quota, runningJob);

        String result = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> quotaService.checkJobSubmission(candidate)));

        assertNull(result);
    }

    @Test
    void getQuotaUsageAggregatesOnlyRunningJobsForRequestedTenant() throws Throwable {
        ResourceQuota quota = quota("tenant-a", true, 128, 1024L, 16, 10);
        Job tenantRunningA = runningJob("job-a", "tenant-a", 16, 128, 2);
        Job tenantRunningB = runningJob("job-b", "tenant-a", 32, 256, 4);
        Job otherTenantRunning = runningJob("job-c", "tenant-b", 64, 512, 8);
        Job completedJob = completedJob("job-d", "tenant-a", 99, 999, 9);

        persistQuotaAndJobs(quota, tenantRunningA, tenantRunningB, otherTenantRunning, completedJob);

        QuotaService.QuotaUsage usage = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> quotaService.getQuotaUsage("tenant-a")));

        assertEquals(128, usage.maxCpuCores());
        assertEquals(48, usage.usedCpuCores());
        assertEquals(1024L, usage.maxMemoryGb());
        assertEquals(384L, usage.usedMemoryGb());
        assertEquals(16, usage.maxGpuCount());
        assertEquals(6, usage.usedGpuCount());
        assertEquals(10, usage.maxConcurrentJobs());
        assertEquals(2, usage.usedConcurrentJobs());
    }

    private void persistQuotaAndJobs(ResourceQuota quota, Job... jobs) throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> quota.persist()
                .replaceWithVoid()
                .chain(() -> persistJobsSequentially(jobs))));
    }

    private io.smallrye.mutiny.Uni<Void> persistJobsSequentially(Job... jobs) {
        io.smallrye.mutiny.Uni<Void> chain = io.smallrye.mutiny.Uni.createFrom().voidItem();
        for (Job job : jobs) {
            chain = chain.chain(() -> job.persist().replaceWithVoid());
        }
        return chain;
    }

    private ResourceQuota quota(String tenantId, boolean enabled, int maxCpu, long maxMemory, int maxGpu,
            int maxJobs) {
        ResourceQuota quota = new ResourceQuota();
        quota.setTenantId(tenantId);
        quota.setEnabled(enabled);
        quota.setMaxCpuCores(maxCpu);
        quota.setMaxMemoryGb(maxMemory);
        quota.setMaxGpuCount(maxGpu);
        quota.setMaxConcurrentJobs(maxJobs);
        return quota;
    }

    private Job runningJob(String id, String tenantId, int cpu, long memory, int gpu) {
        Job job = new Job(id, cpu, memory, gpu, "A100");
        job.setTenantId(tenantId);
        job.setStatus(Job.JobStatus.RUNNING);
        return job;
    }

    private Job pendingJob(String id, String tenantId, int cpu, long memory, int gpu) {
        Job job = new Job(id, cpu, memory, gpu, "A100");
        job.setTenantId(tenantId);
        job.setStatus(Job.JobStatus.PENDING);
        return job;
    }

    private Job completedJob(String id, String tenantId, int cpu, long memory, int gpu) {
        Job job = new Job(id, cpu, memory, gpu, "A100");
        job.setTenantId(tenantId);
        job.setStatus(Job.JobStatus.COMPLETED);
        return job;
    }
}
