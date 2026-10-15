package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.AuditLog;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.grpc.StreamResponse;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class JobDispatchSweeperTest {

    @Inject
    JobDispatchSweeper sweeper;

    @Inject
    JobDispatchStore store;

    @Inject
    StreamRegistry streams;

    @Inject
    JobExecutionService executionService;

    private Job persist(JobStatus status, String nodeId, LocalDateTime scheduledAt, LocalDateTime dispatchedAt)
            throws Throwable {
        Job job = new Job(UUID.randomUUID().toString(), 1, 1, 0, null);
        job.setName("sweep-" + status);
        job.setStatus(status);
        job.setClusterId("default");
        job.setAssignedNodeId(nodeId);
        job.setScheduledAt(scheduledAt);
        job.setDispatchedAt(dispatchedAt);
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> job.persist().replaceWithVoid()));
        return job;
    }

    private Job load(String id) throws Throwable {
        return VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> Job.<Job>findById(id)));
    }

    private void sweep() throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> sweeper.sweepOnce(LocalDateTime.now()));
    }

    @Test
    void claimIsWonByExactlyOneCaller() throws Throwable {
        Job job = persist(JobStatus.SCHEDULED, "n", LocalDateTime.now(), null);

        Boolean first = VertxContextSupport.subscribeAndAwait(() -> store.claim(job.getId()));
        Boolean second = VertxContextSupport.subscribeAndAwait(() -> store.claim(job.getId()));
        assertTrue(first);
        assertEquals(false, second);
        assertEquals(1, load(job.getId()).getDispatchAttempt());

        VertxContextSupport.subscribeAndAwait(() -> store.release(job.getId()));
        assertNull(load(job.getId()).getDispatchedAt());
        assertTrue(VertxContextSupport.subscribeAndAwait(() -> store.claim(job.getId())));
    }

    @Test
    void claimIsRefusedForJobsThatAreNotScheduled() throws Throwable {
        for (JobStatus status : List.of(JobStatus.CANCELLED, JobStatus.RUNNING, JobStatus.COMPLETED)) {
            Job job = persist(status, "n", LocalDateTime.now(), null);
            assertEquals(false, VertxContextSupport.subscribeAndAwait(() -> store.claim(job.getId())));
        }
    }

    @Test
    void undeliveredJobIsRedeliveredOnceWhenTheSatelliteComesBack() throws Throwable {
        String nodeId = "sweep-node-" + UUID.randomUUID();
        Job job = persist(JobStatus.SCHEDULED, nodeId, LocalDateTime.now().minusMinutes(1), null);

        sweep(); // Satellite offline: nothing delivered, job still waiting
        assertEquals(JobStatus.SCHEDULED, load(job.getId()).getStatus());
        assertNull(load(job.getId()).getDispatchedAt());

        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        try {
            sweep();
            sweep();
            StreamResponse command = commands.poll(3, TimeUnit.SECONDS);
            assertNotNull(command);
            assertEquals(job.getId(), command.getCommandId());
            assertNull(commands.poll(1, TimeUnit.SECONDS), "the command must be delivered only once");
            assertNotNull(load(job.getId()).getDispatchedAt());
        } finally {
            connection.cancel();
        }
    }

    @Test
    void recentUndeliveredJobIsNotRacedWithTheKafkaPath() throws Throwable {
        String nodeId = "sweep-node-" + UUID.randomUUID();
        Job job = persist(JobStatus.SCHEDULED, nodeId, LocalDateTime.now(), null);
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        try {
            sweep();
            assertNull(commands.poll(1, TimeUnit.SECONDS));
            assertNull(load(job.getId()).getDispatchedAt());
        } finally {
            connection.cancel();
        }
    }

    @Test
    void undeliveredJobPastTheDeadlineFailsWithoutAnyCommandSent() throws Throwable {
        Job job = persist(JobStatus.SCHEDULED, "sweep-offline", LocalDateTime.now().minusHours(1), null);

        sweep();

        Job stored = load(job.getId());
        assertEquals(JobStatus.FAILED, stored.getStatus());
        assertTrue(stored.getErrorMessage().contains("never sent"));
        assertEquals(0, stored.getDispatchAttempt());
        List<AuditLog> audit = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> AuditLog.findByResourceId(job.getId())));
        assertTrue(audit.stream().anyMatch(a -> a.getEventType() == AuditEventType.JOB_FAILED));
    }

    @Test
    void deliveredJobWithoutProgressBecomesUnknownAndIsNeverResent() throws Throwable {
        String nodeId = "sweep-node-" + UUID.randomUUID();
        Job job = persist(JobStatus.SCHEDULED, nodeId, LocalDateTime.now().minusMinutes(20),
                LocalDateTime.now().minusMinutes(20));
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        try {
            sweep();
            sweep();
            assertEquals(JobStatus.UNKNOWN, load(job.getId()).getStatus());
            assertNull(commands.poll(1, TimeUnit.SECONDS), "an UNKNOWN job must not be re-sent");
        } finally {
            connection.cancel();
        }
    }

    @Test
    void longRunningJobBecomesUnknownAndAnEarlyRunningJobDoesNot() throws Throwable {
        Job stale = persist(JobStatus.RUNNING, "n", LocalDateTime.now().minusHours(5),
                LocalDateTime.now().minusHours(5));
        Job fresh = persist(JobStatus.RUNNING, "n", LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().minusMinutes(1));

        sweep();

        assertEquals(JobStatus.UNKNOWN, load(stale.getId()).getStatus());
        assertEquals(JobStatus.RUNNING, load(fresh.getId()).getStatus());
    }

    @Test
    void lateRealResultResolvesAnUnknownJob() throws Throwable {
        Job job = persist(JobStatus.UNKNOWN, "n", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().minusHours(1));

        VertxContextSupport.subscribeAndAwait(() -> executionService.processJobStatusCallback(
                new com.sc.lcm.core.domain.JobStatusCallback(job.getId(), "n", "COMPLETED", 0, "ok", null, null, 0,
                        LocalDateTime.now(), null)));

        assertEquals(JobStatus.COMPLETED, load(job.getId()).getStatus());
    }
}
