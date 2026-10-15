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
import io.agroal.api.AgroalDataSource;
import io.smallrye.mutiny.subscription.MultiEmitter;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.JobStatusCallback;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.mockito.Mockito;
import org.mockito.AdditionalAnswers;
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

    @Inject
    JobDispatcher dispatcher;

    @Inject
    AgroalDataSource dataSource;

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

    @Test
    void migrationQuarantinesLegacyActiveJobsInsteadOfRedeliveringThem() throws Exception {
        String schema = "review_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                statement.execute("set local search_path to " + schema);
                statement.execute("create table job (id text primary key, status varchar(255), error_message text)");
                statement.execute("insert into job values ('queued','SCHEDULED',null),('running','RUNNING',null),"
                        + "('pending','PENDING',null),('done','COMPLETED',null)");
                try (var migration = getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V2.10.0__Job_Dispatch_State.sql")) {
                    assertNotNull(migration);
                    statement.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
                }
                try (var rows = statement.executeQuery("select id,status from job order by id")) {
                    var statuses = new java.util.HashMap<String,String>();
                    while (rows.next()) statuses.put(rows.getString(1), rows.getString(2));
                    assertEquals("UNKNOWN", statuses.get("queued"), "A legacy queued job may already have executed");
                    assertEquals("UNKNOWN", statuses.get("running"));
                    assertEquals("PENDING", statuses.get("pending"));
                    assertEquals("COMPLETED", statuses.get("done"));
                }
            } finally {
                connection.rollback(); // removes this test schema and restores search_path
            }
        }
    }

    @Test
    void emissionFailureAfterHandoffDoesNotAllowAutomaticDuplicateExecution() throws Throwable {
        String nodeId = "uncertain-" + UUID.randomUUID();
        Job job = persist(JobStatus.SCHEDULED, nodeId, LocalDateTime.now().minusMinutes(1), null);
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(emitter -> {
            MultiEmitter<? super StreamResponse> uncertain = Mockito.mock(MultiEmitter.class,
                    AdditionalAnswers.delegatesTo(emitter));
            Mockito.doAnswer(call -> {
                emitter.emit((StreamResponse) call.getArgument(0));
                throw new IllegalStateException("write failed after handoff");
            }).when(uncertain).emit(Mockito.any());
            streams.register(nodeId, uncertain);
        }).subscribe().with(commands::add);
        try {
            VertxContextSupport.subscribeAndAwait(() -> dispatcher.dispatch(job));
            VertxContextSupport.subscribeAndAwait(() -> dispatcher.dispatch(job));
            assertEquals(1, commands.size(), "An ambiguous write must not release the claim and re-emit");
            Job stored = load(job.getId());
            assertEquals(JobStatus.UNKNOWN, stored.getStatus());
            assertNotNull(stored.getDispatchedAt());
            assertEquals(1, stored.getDispatchAttempt());
        } finally {
            connection.cancel();
        }
    }

    @Test
    void duplicateDispatchCannotFailAnAlreadyRunningSshJobWhenItsTargetChanges() throws Throwable {
        String nodeId = "already-running-" + UUID.randomUUID();
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.33.0.5");
        device.setStatus(DiscoveredDevice.DiscoveryStatus.APPROVED);
        device.setDiscoveryMethod(DiscoveredDevice.DiscoveryMethod.MANUAL);
        Job job = new Job(UUID.randomUUID().toString(), 1, 1, 0, null);
        job.setName("duplicate-target-change");
        job.setStatus(JobStatus.SCHEDULED);
        job.setScheduledAt(LocalDateTime.now());
        job.setAssignedNodeId(nodeId);
        job.setExecutionType(Job.ExecutionType.SSH);
        job.setTargetHost("10.33.0.5");
        job.setExecutionPayload("{\"host\":\"10.33.0.5\",\"task\":\"SYSTEM_INFO\"}");
        try {
            VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> device.persist()
                    .chain(() -> { job.setTargetDeviceId(device.getId()); return job.persist(); }).replaceWithVoid()));
            VertxContextSupport.subscribeAndAwait(() -> dispatcher.dispatch(job));
            assertNotNull(commands.poll(2, TimeUnit.SECONDS));
            VertxContextSupport.subscribeAndAwait(() -> executionService.processJobStatusCallback(
                    new JobStatusCallback(job.getId(), nodeId, "RUNNING", 0, "", null, null, 0,
                            LocalDateTime.now(), null)));
            VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() ->
                    DiscoveredDevice.update("ipAddress = ?1 where id = ?2", "10.33.0.9", device.getId())));
            VertxContextSupport.subscribeAndAwait(() -> dispatcher.dispatch(job));
            assertEquals(JobStatus.RUNNING, load(job.getId()).getStatus());
            assertTrue(commands.isEmpty(), "A duplicate must not send a second command");
        } finally {
            connection.cancel();
        }
    }

    @Test
    void timeoutRechecksTheDeadlineAfterWaitingForAConcurrentClaimRefresh() throws Throwable {
        Job job = persist(JobStatus.SCHEDULED, "refresh-" + UUID.randomUUID(),
                LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(20));
        CompletableFuture<Void> sweep = null;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.prepareStatement("select pg_backend_pid()")) {
                try (var result = statement.executeQuery()) { result.next(); blockerPid = result.getInt(1); }
            }
            try (var statement = blocker.prepareStatement("select id from job where id = ? for update")) {
                statement.setString(1, job.getId());
                try (var result = statement.executeQuery()) { assertTrue(result.next()); }
            }
            try {
                sweep = CompletableFuture.runAsync(() -> {
                    try { sweep(); } catch (Throwable error) { throw new CompletionException(error); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                try (Connection observer = dataSource.getConnection();
                        var statement = observer.prepareStatement(
                                "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))")) {
                    statement.setInt(1, blockerPid);
                    do {
                        try (var result = statement.executeQuery()) {
                            result.next(); blocked = result.getInt(1) > 0;
                        }
                        if (!blocked) Thread.sleep(25);
                    } while (!blocked && System.nanoTime() < deadline);
                }
                assertTrue(blocked, "The timeout writer must have read the stale candidate and be waiting");
                try (var statement = blocker.prepareStatement("update job set dispatched_at = ? where id = ?")) {
                    statement.setObject(1, LocalDateTime.now());
                    statement.setString(2, job.getId()); statement.executeUpdate();
                }
                blocker.commit();
                sweep.get(10, TimeUnit.SECONDS);
                assertEquals(JobStatus.SCHEDULED, load(job.getId()).getStatus(),
                        "A freshly claimed job has not timed out");
            } finally {
                blocker.rollback();
                if (sweep != null) sweep.get(10, TimeUnit.SECONDS);
            }
        }
    }
}
