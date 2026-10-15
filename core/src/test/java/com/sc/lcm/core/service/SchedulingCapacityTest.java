package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.domain.Node;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 counter-examples: placement honours existing occupancy, atomically, and frees it on completion. */
@QuarkusTest
class SchedulingCapacityTest {

    @Inject
    JobExecutionService executionService;

    @InjectMock
    NodeSpecsProvider specs;

    private final java.util.Map<String, Node> nodes = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void stubSpecs() {
        Mockito.when(specs.getNodeSpecs(Mockito.any())).thenAnswer(
                call -> nodes.get(call.<Satellite>getArgument(0).getId()));
    }

    @Inject
    JobDispatchSweeper sweeper;

    private String node(int cpu, long mem, int gpu, String model) throws Throwable {
        String id = "cap-" + UUID.randomUUID();
        Satellite satellite = new Satellite(id, "default", id, "10.0.0.1", "linux", "test");
        satellite.setLastHeartbeat(LocalDateTime.now());
        VertxContextSupport.subscribeAndAwait(
                () -> Panache.withTransaction(() -> satellite.persist().replaceWithVoid()));
        nodes.put(id, new Node(id, cpu, mem, gpu, model));
        return id;
    }

    private Job pending(int cpu, long mem, int gpu, String model) throws Throwable {
        Job job = new Job(UUID.randomUUID().toString(), cpu, mem, gpu, model);
        job.setName("cap");
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> job.persist().replaceWithVoid()));
        return job;
    }

    private Job load(String id) throws Throwable {
        return VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> Job.<Job>findById(id)));
    }

    private void setStatus(String id, JobStatus status) throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(
                () -> Job.update("status = ?1 where id = ?2", status, id)).replaceWithVoid());
    }

    @Test
    void secondEightCoreJobCannotShareAnEightCoreNodeUntilTheFirstCompletes() throws Throwable {
        String node = node(8, 64, 0, null);
        Job a = pending(8, 8, 0, null);
        Job b = pending(8, 8, 0, null);

        assertTrue(executionService.recordScheduledDispatchBlocking(a.getId(), node));
        assertFalse(executionService.recordScheduledDispatchBlocking(b.getId(), node));
        assertEquals(JobStatus.PENDING, load(b.getId()).getStatus());

        setStatus(a.getId(), JobStatus.COMPLETED); // occupancy is derived from job rows: completion releases it
        assertTrue(executionService.recordScheduledDispatchBlocking(b.getId(), node));
        assertEquals(JobStatus.SCHEDULED, load(b.getId()).getStatus());
    }

    @Test
    void unknownJobKeepsHoldingCapacityUntilResolved() throws Throwable {
        String node = node(8, 64, 0, null);
        Job a = pending(8, 8, 0, null);
        assertTrue(executionService.recordScheduledDispatchBlocking(a.getId(), node));
        setStatus(a.getId(), JobStatus.UNKNOWN);

        Job b = pending(8, 8, 0, null);
        assertFalse(executionService.recordScheduledDispatchBlocking(b.getId(), node));
    }

    @Test
    void concurrentPlacementsOnOneNodeAdmitExactlyOne() throws Throwable {
        String node = node(8, 64, 0, null);
        Job a = pending(8, 8, 0, null);
        Job b = pending(8, 8, 0, null);

        CompletableFuture<Boolean> fa = CompletableFuture.supplyAsync(
                () -> executionService.recordScheduledDispatchBlocking(a.getId(), node));
        CompletableFuture<Boolean> fb = CompletableFuture.supplyAsync(
                () -> executionService.recordScheduledDispatchBlocking(b.getId(), node));

        assertEquals(1, (fa.get() ? 1 : 0) + (fb.get() ? 1 : 0));
    }

    @Test
    void unsupportedGpuModelIsNeverPlaced() throws Throwable {
        String node = node(64, 512, 8, "A100");
        Job job = pending(1, 1, 1, "H100");
        assertFalse(executionService.recordScheduledDispatchBlocking(job.getId(), node));
        assertEquals(JobStatus.PENDING, load(job.getId()).getStatus());
    }

    @Test
    void pendingJobPastTheDeadlineFailsWithAnExplanationAndNothingDispatched() throws Throwable {
        Job job = pending(1000, 1, 0, null);
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(
                () -> Job.update("createdAt = ?1 where id = ?2", LocalDateTime.now().minusHours(1), job.getId()))
                .replaceWithVoid());

        VertxContextSupport.subscribeAndAwait(() -> sweeper.sweepOnce(LocalDateTime.now()));

        Job failed = load(job.getId());
        assertEquals(JobStatus.FAILED, failed.getStatus());
        assertEquals(JobDispatchSweeper.NO_CAPACITY_MESSAGE, failed.getErrorMessage());
        assertEquals(0, failed.getDispatchAttempt());
    }
}
