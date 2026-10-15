package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;

import java.time.LocalDateTime;

/**
 * Database side of dispatching. {@link #claim} is the single gate every sender passes through, so a job is handed
 * to a command stream at most once per claim even when the Kafka consumer and the sweeper race.
 */
@ApplicationScoped
public class JobDispatchStore {

    @Inject
    AuditService auditService;

    /** @return true only for the caller that moved the job from "not dispatched" to "dispatched" while SCHEDULED. */
    public Uni<Boolean> claim(String jobId) {
        return Panache.withTransaction(() -> Job.update(
                "dispatchedAt = ?1, dispatchAttempt = dispatchAttempt + 1 where id = ?2 and status = ?3 and dispatchedAt is null",
                LocalDateTime.now(), jobId, JobStatus.SCHEDULED))
                .map(updated -> updated > 0);
    }

    /** Gives a claim back after the command could not be emitted, so the sweeper can retry delivery. */
    public Uni<Void> release(String jobId) {
        return Panache.withTransaction(() -> Job.update(
                "dispatchedAt = null where id = ?1 and status = ?2", jobId, JobStatus.SCHEDULED))
                .replaceWithVoid();
    }

    /** An emit was attempted, so a write error cannot establish that the executor did not receive it. */
    public Uni<Void> recordUncertainSend(String jobId) {
        return Panache.withTransaction(() -> Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE)
                .chain(job -> {
                    // An executor callback may already have supplied stronger evidence.
                    if (job == null || job.getStatus() != JobStatus.SCHEDULED || job.getDispatchedAt() == null) {
                        return Uni.createFrom().voidItem();
                    }
                    job.setStatus(JobStatus.UNKNOWN);
                    job.setErrorMessage("Command emission failed after dispatch was claimed; the result is unknown. "
                            + "Verify on the target; do not resend automatically.");
                    return auditService.logEvent(AuditEventType.JOB_UNKNOWN, "JOB", jobId, "SYSTEM",
                            job.getTenantId(), "{\"action\":\"unknown\",\"reason\":\"uncertain_send\"}");
                }));
    }
}
