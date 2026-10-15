package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;

/**
 * Database side of dispatching. {@link #claim} is the single gate every sender passes through, so a job is handed
 * to a command stream at most once per claim even when the Kafka consumer and the sweeper race.
 */
@ApplicationScoped
public class JobDispatchStore {

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
}
