package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/** Reads the authoritative job status from the database right before a command is sent. */
@ApplicationScoped
public class JobStateReader {

    public Uni<Boolean> isScheduled(String jobId) {
        return Panache.withSession(() -> Job.<Job>findById(jobId))
                .map(job -> job != null && job.getStatus() == JobStatus.SCHEDULED);
    }
}
