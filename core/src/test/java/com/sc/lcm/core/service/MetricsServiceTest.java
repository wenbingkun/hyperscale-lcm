package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Satellite;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class MetricsServiceTest {

    @Inject
    MetricsService metricsService;

    @Inject
    MeterRegistry meterRegistry;

    @BeforeEach
    void resetPersistence() throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> Job.deleteAll()
                .chain(() -> Satellite.deleteAll())
                .replaceWithVoid()));
    }

    @Test
    void updateGaugesReflectsDatabaseState() throws Throwable {
        Satellite activeSatellite = satellite("sat-online", LocalDateTime.now().minusSeconds(30));
        Satellite staleSatellite = satellite("sat-stale", LocalDateTime.now().minusMinutes(5));
        Job pendingJob = job("job-pending", Job.JobStatus.PENDING);
        Job runningJob = job("job-running", Job.JobStatus.RUNNING);
        Job completedJob = job("job-completed", Job.JobStatus.COMPLETED);

        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> Satellite.persist(activeSatellite)
                .chain(() -> Satellite.persist(staleSatellite))
                .chain(() -> pendingJob.persist())
                .chain(() -> runningJob.persist())
                .chain(() -> completedJob.persist())
                .replaceWithVoid()));

        VertxContextSupport.subscribeAndAwait(() -> metricsService.updateGauges());

        assertEquals(1.0, meterRegistry.get("lcm_nodes_online").gauge().value());
        assertEquals(1.0, meterRegistry.get("lcm_jobs_pending").gauge().value());
        assertEquals(1.0, meterRegistry.get("lcm_jobs_running").gauge().value());
    }

    @Test
    void recordMethodsIncrementCountersAndTimer() {
        double submittedBefore = meterRegistry.get("lcm_jobs_submitted_total").counter().count();
        double completedBefore = meterRegistry.get("lcm_jobs_completed_total").counter().count();
        double failedBefore = meterRegistry.get("lcm_jobs_failed_total").counter().count();
        double heartbeatBefore = meterRegistry.get("lcm_heartbeats_received_total").counter().count();
        long timerCountBefore = meterRegistry.get("lcm_scheduling_duration_seconds").timer().count();

        metricsService.recordJobSubmitted();
        metricsService.recordJobCompleted();
        metricsService.recordJobFailed();
        metricsService.recordHeartbeat();
        Timer.Sample sample = metricsService.startSchedulingTimer();
        metricsService.stopSchedulingTimer(sample);

        assertEquals(submittedBefore + 1.0, meterRegistry.get("lcm_jobs_submitted_total").counter().count());
        assertEquals(completedBefore + 1.0, meterRegistry.get("lcm_jobs_completed_total").counter().count());
        assertEquals(failedBefore + 1.0, meterRegistry.get("lcm_jobs_failed_total").counter().count());
        assertEquals(heartbeatBefore + 1.0, meterRegistry.get("lcm_heartbeats_received_total").counter().count());
        assertEquals(timerCountBefore + 1, meterRegistry.get("lcm_scheduling_duration_seconds").timer().count());
    }

    private Satellite satellite(String id, LocalDateTime lastHeartbeat) {
        Satellite satellite = new Satellite(id, "default", id, "10.0.0.1", "Linux", "1.0.0");
        satellite.setLastHeartbeat(lastHeartbeat);
        return satellite;
    }

    private Job job(String id, Job.JobStatus status) {
        Job job = new Job(id, 8, 64, 1, "A100");
        job.setStatus(status);
        return job;
    }
}
