package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.grpc.StreamResponse;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SshDispatchIntegrationTest {
    @Inject @Channel("job-queue-out") Emitter<Job> queue;
    @Inject StreamRegistry streams;

    @Test
    void kafkaDispatchesAnUnchangedApprovedTarget() throws Throwable {
        exercise("unchanged");
    }

    @Test
    void kafkaRefusesAMovedTargetAndPersistsFailure() throws Throwable {
        exercise("moved");
    }

    @Test void kafkaRefusesRejectedTarget() throws Throwable { exercise("rejected"); }
    @Test void kafkaRefusesDeletedTarget() throws Throwable { exercise("missing"); }
    @Test void kafkaRefusesPayloadHostMismatch() throws Throwable { exercise("payload"); }

    @Test
    void kafkaDoesNotDispatchAJobCancelledWhileItsMessageWasInFlight() throws Throwable {
        String nodeId = "dispatch-cancel-" + UUID.randomUUID();
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        Job job = new Job(UUID.randomUUID().toString(), 1, 1, 0, null);
        job.setName("cancelled-in-flight");
        job.setStatus(Job.JobStatus.CANCELLED);
        job.setAssignedNodeId(nodeId);
        try {
            VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> job.persist().replaceWithVoid()));
            queue.send(job).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNull(commands.poll(5, TimeUnit.SECONDS), "A cancelled job must not reach the Satellite");
            Job stored = VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withSession(() -> Job.<Job>findById(job.getId())));
            assertEquals(Job.JobStatus.CANCELLED, stored.getStatus());
        } finally {
            connection.cancel();
        }
    }

    private void exercise(String scenario) throws Throwable {
        String nodeId = "dispatch-review-" + UUID.randomUUID();
        var commands = new LinkedBlockingQueue<StreamResponse>();
        Cancellable connection = Multi.createFrom().<StreamResponse>emitter(
                emitter -> streams.register(nodeId, emitter)).subscribe().with(commands::add);
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress(scenario.equals("moved") ? "10.30.0.9" : "10.30.0.5");
        device.setStatus(scenario.equals("rejected") ? DiscoveredDevice.DiscoveryStatus.REJECTED : DiscoveredDevice.DiscoveryStatus.APPROVED);
        device.setDiscoveryMethod(DiscoveredDevice.DiscoveryMethod.MANUAL);
        Job job = new Job(UUID.randomUUID().toString(), 1, 1, 0, null);
        job.setName("dispatch-review");
        job.setExecutionType(Job.ExecutionType.SSH);
        job.setStatus(Job.JobStatus.SCHEDULED);
        job.setAssignedNodeId(nodeId);
        job.setTargetHost("10.30.0.5");
        job.setExecutionPayload("{\"host\":\"10.30.0.5\",\"port\":22,\"user\":\"ops\",\"keyRef\":\"lab_ed25519\",\"task\":\"SYSTEM_INFO\"}");
        try {
            VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> device.persist()
                    .chain(() -> { job.setTargetDeviceId(scenario.equals("missing") ? UUID.randomUUID().toString() : device.getId());
                        if (scenario.equals("payload")) job.setExecutionPayload("{\"host\":\"10.30.0.66\"}");
                        return job.persist(); }).replaceWithVoid()));
            queue.send(job).toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (scenario.equals("unchanged")) {
                StreamResponse command = commands.poll(8, TimeUnit.SECONDS);
                assertNotNull(command, "The real Kafka consumer must send an approved SSH task");
                assertEquals(job.getId(), command.getCommandId());
                assertEquals("EXEC_SSH", command.getCommandType());
            } else {
                long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                Job stored;
                do {
                    stored = VertxContextSupport.subscribeAndAwait(
                            () -> Panache.withSession(() -> Job.<Job>findById(job.getId())));
                    if (stored.getStatus() == Job.JobStatus.FAILED) break;
                    Thread.sleep(100);
                } while (System.nanoTime() < deadline);
                assertEquals(Job.JobStatus.FAILED, stored.getStatus(), "Refusal must persist through the real Kafka consumer");
                String expected = switch (scenario) {
                    case "moved" -> "address changed";
                    case "rejected" -> "no longer approved";
                    case "missing" -> "no longer exists";
                    default -> "payload host";
                };
                assertTrue(stored.getErrorMessage().contains(expected), stored.getErrorMessage());
                assertTrue(commands.isEmpty(), "The moved target must receive no command");
            }
        } finally {
            connection.cancel();
        }
    }
}
