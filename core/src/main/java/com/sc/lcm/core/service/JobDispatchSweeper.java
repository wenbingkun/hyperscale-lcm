package com.sc.lcm.core.service;

import com.sc.lcm.core.api.DashboardWebSocket;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * Drives every SCHEDULED/RUNNING job to an explainable state (documentation/JOB_LIFECYCLE_CONTRACT.md §4):
 * undelivered jobs are re-sent and eventually FAILED (no command was ever emitted, so nothing ran); delivered jobs
 * that show no result in time become UNKNOWN (the command may have run) and are never re-sent automatically.
 */
@ApplicationScoped
@Slf4j
public class JobDispatchSweeper {

    static final String UNDELIVERED_MESSAGE =
            "Not delivered to the executor (Satellite offline or command stream unavailable); the command was never sent";
    static final String NO_CAPACITY_MESSAGE =
            "No node with sufficient free capacity was found in time; the job was never dispatched and nothing ran";
    static final String NO_START_MESSAGE =
            "Dispatch was claimed but no status was reported in time; the result is unknown and needs manual verification";
    static final String NO_RESULT_MESSAGE =
            "Job has been running longer than the allowed time without a result; the result is unknown and needs manual verification";

    @Inject
    JobDispatcher dispatcher;

    @Inject
    SchedulingService schedulingService;

    @Inject
    PartitionedSchedulingService partitionedSchedulingService;

    @ConfigProperty(name = "lcm.scheduling.partitioned.enabled", defaultValue = "false")
    boolean partitionedSchedulingEnabled;

    @ConfigProperty(name = "lcm.jobs.pending-retry-interval")
    Duration pendingRetryInterval;

    @ConfigProperty(name = "lcm.jobs.pending-timeout")
    Duration pendingTimeout;

    /** Last scheduling retry per PENDING job, to keep retries spaced by pending-retry-interval. */
    private final java.util.concurrent.ConcurrentHashMap<String, LocalDateTime> lastPendingRetry =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    AuditService auditService;

    @Inject
    DashboardWebSocket dashboardWebSocket;

    @Inject
    MetricsService metricsService;

    @ConfigProperty(name = "lcm.jobs.redeliver-grace")
    Duration redeliverGrace;

    @ConfigProperty(name = "lcm.jobs.dispatch-timeout")
    Duration dispatchTimeout;

    @ConfigProperty(name = "lcm.jobs.start-timeout")
    Duration startTimeout;

    @ConfigProperty(name = "lcm.jobs.run-timeout")
    Duration runTimeout;

    @Scheduled(every = "{lcm.jobs.sweeper.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> scheduledSweep() {
        return sweepOnce(LocalDateTime.now())
                .onFailure().invoke(e -> log.error("Job dispatch sweep failed", e))
                .onFailure().recoverWithNull();
    }

    public Uni<Void> sweepOnce(LocalDateTime now) {
        return failUnschedulable(now)
                .chain(() -> retryPending(now))
                .chain(() -> failUndelivered(now))
                .chain(() -> redeliver(now))
                .chain(() -> markUnknown(now));
    }

    /** PENDING past the deadline never found a node (or its scheduling was lost): nothing was dispatched. */
    private Uni<Void> failUnschedulable(LocalDateTime now) {
        LocalDateTime deadline = now.minus(pendingTimeout);
        return forEach(
                () -> Job.<Job>list("status = ?1 and createdAt < ?2", JobStatus.PENDING, deadline),
                job -> Panache.withTransaction(() -> Job.update(
                        "status = ?1, errorMessage = ?2, exitCode = -1, completedAt = ?3 "
                                + "where id = ?4 and status = ?5 and createdAt < ?6",
                        JobStatus.FAILED, NO_CAPACITY_MESSAGE, now, job.getId(), JobStatus.PENDING, deadline))
                        .chain(updated -> {
                            if (updated == 0) {
                                return Uni.createFrom().voidItem();
                            }
                            log.warn("Job {} failed: {}", job.getId(), NO_CAPACITY_MESSAGE);
                            lastPendingRetry.remove(job.getId());
                            metricsService.recordJobFailed();
                            announce(job, JobStatus.FAILED);
                            return auditService.logJobFailed(job.getId(), "SYSTEM", job.getTenantId(),
                                    NO_CAPACITY_MESSAGE);
                        }));
    }

    /**
     * Re-triggers scheduling for PENDING jobs: capacity may have been freed, a Satellite may have appeared, or the
     * original attempt may have been lost with a Core restart. Placement itself stays atomic in the DB.
     */
    private Uni<Void> retryPending(LocalDateTime now) {
        lastPendingRetry.values().removeIf(at -> at.isBefore(now.minus(pendingTimeout).minus(pendingTimeout)));
        return forEach(
                () -> Job.<Job>list("status = ?1 and createdAt < ?2", JobStatus.PENDING,
                        now.minus(pendingRetryInterval)),
                job -> {
                    LocalDateTime last = lastPendingRetry.get(job.getId());
                    if (last != null && last.isAfter(now.minus(pendingRetryInterval))) {
                        return Uni.createFrom().voidItem();
                    }
                    lastPendingRetry.put(job.getId(), now);
                    log.info("Re-triggering scheduling for pending job {}", job.getId());
                    return (partitionedSchedulingEnabled
                            ? partitionedSchedulingService.scheduleByZone(job).replaceWithVoid()
                            : schedulingService.scheduleJob(job));
                });
    }

    private Uni<Void> failUndelivered(LocalDateTime now) {
        return forEach(
                () -> Job.<Job>list("status = ?1 and dispatchedAt is null and scheduledAt < ?2",
                        JobStatus.SCHEDULED, now.minus(dispatchTimeout)),
                job -> Panache.withTransaction(() -> Job.update(
                        "status = ?1, errorMessage = ?2, exitCode = -1, completedAt = ?3 "
                                + "where id = ?4 and status = ?5 and dispatchedAt is null and scheduledAt < ?6",
                        JobStatus.FAILED, UNDELIVERED_MESSAGE, now, job.getId(), JobStatus.SCHEDULED,
                        now.minus(dispatchTimeout)))
                        .chain(updated -> {
                            if (updated == 0) {
                                return Uni.createFrom().voidItem();
                            }
                            log.warn("Job {} failed as undelivered", job.getId());
                            metricsService.recordJobFailed();
                            announce(job, JobStatus.FAILED);
                            return auditService.logJobFailed(job.getId(), "SYSTEM", job.getTenantId(),
                                    UNDELIVERED_MESSAGE);
                        }));
    }

    private Uni<Void> redeliver(LocalDateTime now) {
        return forEach(
                () -> Job.<Job>list("status = ?1 and dispatchedAt is null and scheduledAt < ?2",
                        JobStatus.SCHEDULED, now.minus(redeliverGrace)),
                dispatcher::dispatch);
    }

    private Uni<Void> markUnknown(LocalDateTime now) {
        Uni<Void> notStarted = forEach(
                () -> Job.<Job>list("status = ?1 and dispatchedAt is not null and dispatchedAt < ?2",
                        JobStatus.SCHEDULED, now.minus(startTimeout)),
                job -> toUnknown(job, JobStatus.SCHEDULED, NO_START_MESSAGE, now.minus(startTimeout)));
        return notStarted.chain(() -> forEach(
                () -> Job.<Job>list("status = ?1 and coalesce(dispatchedAt, scheduledAt) < ?2",
                        JobStatus.RUNNING, now.minus(runTimeout)),
                job -> toUnknown(job, JobStatus.RUNNING, NO_RESULT_MESSAGE, now.minus(runTimeout))));
    }

    private Uni<Void> toUnknown(Job job, JobStatus from, String message, LocalDateTime deadline) {
        String clock = from == JobStatus.SCHEDULED ? "dispatchedAt" : "coalesce(dispatchedAt, scheduledAt)";
        return Panache.withTransaction(() -> Job.update(
                "status = ?1, errorMessage = ?2 where id = ?3 and status = ?4 and " + clock + " < ?5",
                JobStatus.UNKNOWN, message, job.getId(), from, deadline))
                .chain(updated -> {
                    if (updated == 0) {
                        return Uni.createFrom().voidItem();
                    }
                    log.warn("Job {} marked UNKNOWN (was {}): {}", job.getId(), from, message);
                    announce(job, JobStatus.UNKNOWN);
                    return auditService.logEvent(AuditEventType.JOB_UNKNOWN, "JOB", job.getId(), "SYSTEM",
                            job.getTenantId(), "{\"action\":\"unknown\",\"from\":\"" + from + "\"}");
                });
    }

    private void announce(Job job, JobStatus status) {
        dashboardWebSocket.broadcastJobStatus(job.getId(), job.getName(), status.name(), job.getAssignedNodeId(),
                status == JobStatus.FAILED ? -1 : null);
    }

    /** Runs the action per job, one at a time; a failure on one job is logged and does not stop the others. */
    private Uni<Void> forEach(java.util.function.Supplier<Uni<List<Job>>> query, Function<Job, Uni<Void>> action) {
        return Panache.withSession(query::get)
                .onItem().transformToMulti(jobs -> Multi.createFrom().iterable(jobs))
                .onItem().transformToUniAndConcatenate(job -> action.apply(job)
                        .onFailure().invoke(e -> log.error("Sweep action failed for job {}", job.getId(), e))
                        .onFailure().recoverWithNull())
                .collect().asList()
                .replaceWithVoid();
    }
}
