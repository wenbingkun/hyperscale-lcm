package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.Satellite;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标服务 (P4-3)
 * 
 * 收集并暴露系统运行指标到 Prometheus
 */
@ApplicationScoped
@Slf4j
public class MetricsService {

    @Inject
    MeterRegistry registry;

    @Inject
    SatelliteStateCache stateCache;

    // ============== Gauge 值 (可变) ==============
    private final AtomicLong onlineNodesGauge = new AtomicLong(0);
    private final AtomicLong pendingJobsGauge = new AtomicLong(0);
    private final AtomicLong runningJobsGauge = new AtomicLong(0);

    // ============== Counter (累计) ==============
    private Counter jobsSubmittedCounter;
    private Counter jobsCompletedCounter;
    private Counter jobsFailedCounter;
    private Counter heartbeatsReceivedCounter;

    // ============== Timer (耗时分布) ==============
    private Timer schedulingDurationTimer;

    @PostConstruct
    void init() {
        // 注册 Gauge
        Gauge.builder("lcm_nodes_online", onlineNodesGauge, AtomicLong::get)
                .description("Number of online nodes")
                .register(registry);

        Gauge.builder("lcm_jobs_pending", pendingJobsGauge, AtomicLong::get)
                .description("Number of pending jobs")
                .register(registry);

        Gauge.builder("lcm_jobs_running", runningJobsGauge, AtomicLong::get)
                .description("Number of running jobs")
                .register(registry);

        // 注册 Counter
        jobsSubmittedCounter = Counter.builder("lcm_jobs_submitted_total")
                .description("Total number of jobs submitted")
                .register(registry);

        jobsCompletedCounter = Counter.builder("lcm_jobs_completed_total")
                .description("Total number of jobs completed")
                .register(registry);

        jobsFailedCounter = Counter.builder("lcm_jobs_failed_total")
                .description("Total number of jobs failed")
                .register(registry);

        heartbeatsReceivedCounter = Counter.builder("lcm_heartbeats_received_total")
                .description("Total number of heartbeats received")
                .register(registry);

        // 注册 Timer
        schedulingDurationTimer = Timer.builder("lcm_scheduling_duration_seconds")
                .description("Time taken to schedule a job")
                .register(registry);

        log.info("📊 Metrics service initialized");
    }

    /**
     * 定期更新 Gauge 值
     */
    @Scheduled(every = "10s")
    void updateGauges() {
        // 更新在线节点数
        Satellite.findActive(LocalDateTime.now().minusMinutes(2))
                .subscribe().with(
                        satellites -> onlineNodesGauge.set(satellites.size()),
                        error -> log.error("Failed to update online nodes gauge", error));

        // 更新作业状态计数
        Job.countByStatus(JobStatus.PENDING)
                .subscribe().with(
                        count -> pendingJobsGauge.set(count),
                        error -> log.error("Failed to update pending jobs gauge", error));

        Job.countByStatus(JobStatus.RUNNING)
                .subscribe().with(
                        count -> runningJobsGauge.set(count),
                        error -> log.error("Failed to update running jobs gauge", error));
    }

    // ============== 记录方法 ==============

    public void recordJobSubmitted() {
        jobsSubmittedCounter.increment();
    }

    public void recordJobCompleted() {
        jobsCompletedCounter.increment();
    }

    public void recordJobFailed() {
        jobsFailedCounter.increment();
    }

    public void recordHeartbeat() {
        heartbeatsReceivedCounter.increment();
    }

    /**
     * 记录调度耗时
     */
    public Timer.Sample startSchedulingTimer() {
        return Timer.start(registry);
    }

    public void stopSchedulingTimer(Timer.Sample sample) {
        sample.stop(schedulingDurationTimer);
    }
}
