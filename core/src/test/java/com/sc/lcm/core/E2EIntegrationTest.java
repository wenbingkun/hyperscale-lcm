package com.sc.lcm.core;

import com.sc.lcm.core.api.JobResource.JobRequest;
import com.sc.lcm.core.api.JobResource.JobStatusResponse;
import com.sc.lcm.core.grpc.HeartbeatRequest;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.JobStatusUpdate;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.StreamRequest;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test that exercises the full job lifecycle.
 *
 * Infrastructure dependencies (PostgreSQL, Kafka, Redis) are started
 * automatically by Quarkus DevServices backed by Testcontainers.
 * Ensure Docker is available on the test host.
 */
@QuarkusTest
public class E2EIntegrationTest {

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "unused")
    String kafkaBrokers;

    @GrpcClient("lcm")
    LcmService grpcClient;

    @Test
    public void testEndToEndJobLifecycle() throws Exception {
        try (KafkaCompanion companion = new KafkaCompanion(kafkaBrokers)) {
            AtomicReference<MultiEmitter<? super StreamRequest>> streamEmitterRef = new AtomicReference<>();
            CompletableFuture<String> commandTypeFuture = new CompletableFuture<>();

            // 1. Register Satellite (Mocking Satellite startup)
            RegisterRequest registerRequest = RegisterRequest.newBuilder()
                    .setHostname("test-node-1")
                    .setIpAddress("192.168.1.100")
                    .setAgentVersion("1.0.0")
                    .setOsVersion("Ubuntu 24.04")
                    .build();

            var registerResponse = grpcClient.registerSatellite(registerRequest)
                    .await().atMost(Duration.ofSeconds(5));

            // Use the assigned ID from the registration response
            String satelliteId = registerResponse.getAssignedId();
            assertNotNull(satelliteId, "Registration should return an assigned ID");

            // 2. Send Heartbeat to ensure node is active and has resources
            HeartbeatRequest heartbeat = HeartbeatRequest.newBuilder()
                    .setSatelliteId(satelliteId)
                    .setClusterId("default")
                    .setCpuUsagePercent(10.0f)
                    .setMemoryUsedBytes(1024L)
                    .setMemoryTotalBytes(8192L)
                    .setGpuCount(1)
                    .addGpuMetrics(
                            com.sc.lcm.core.grpc.GpuMetric.newBuilder().setIndex(0)
                                    .setUtilizationPercent(0).build())
                    .build();
            grpcClient.sendHeartbeat(heartbeat).await().atMost(Duration.ofSeconds(5));

            // 3. Keep a bi-directional stream open so the dispatcher can deliver commands.
            Multi<StreamRequest> requestStream = Multi.createFrom().emitter(emitter -> {
                streamEmitterRef.set(emitter);
                emitter.emit(StreamRequest.newBuilder()
                        .setSatelliteId(satelliteId)
                        .setInit(true)
                        .build());
            });
            Cancellable streamSubscription = grpcClient.connectStream(requestStream).subscribe().with(resp -> {
                commandTypeFuture.complete(resp.getCommandType());
            });

            // 4. Authenticate via HTTP API
            String token = given()
                    .contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"admin123\",\"tenantId\":\"default\"}")
                    .when()
                    .post("/api/auth/login")
                    .then()
                    .statusCode(200)
                    .extract().path("token");

            // 5. Submit a new Job via HTTP API
            JobRequest jobReq = new JobRequest(
                    "E2E Test Job",
                    "Integration test job",
                    1,
                    1,
                    0, // no GPU required to ensure it schedules easily
                    null,
                    false,
                    0,
                    "default",
                    "default");

            String jobId = given()
                    .header("Authorization", "Bearer " + token)
                    .contentType(ContentType.JSON)
                    .body(jobReq)
                    .when()
                    .post("/api/jobs")
                    .then()
                    .statusCode(201)
                    .extract().path("id");

            assertNotNull(jobId, "Job ID should not be null after submission");

            String commandType = commandTypeFuture.get(5, TimeUnit.SECONDS);
            assertTrue(commandType != null && !commandType.isBlank(), "Core should dispatch a command over the stream");

            JobStatusResponse scheduledStatus = awaitJobStatus(token, jobId, status ->
                    "SCHEDULED".equals(status.status())
                            && satelliteId.equals(status.assignedNodeId()),
                    Duration.ofSeconds(15));
            assertEquals("SCHEDULED", scheduledStatus.status(), "Job should be marked scheduled after dispatch");
            assertEquals(satelliteId, scheduledStatus.assignedNodeId(), "Scheduled job should target the registered node");
            assertNotNull(scheduledStatus.scheduledAt(), "Scheduled job should record its scheduled timestamp");

            // 6. Send the job completion update on the same open stream.
            MultiEmitter<? super StreamRequest> streamEmitter = streamEmitterRef.get();
            assertNotNull(streamEmitter, "Expected an initialized stream emitter");
            streamEmitter.emit(StreamRequest.newBuilder()
                    .setSatelliteId(satelliteId)
                    .setStatusUpdate(JobStatusUpdate.newBuilder()
                            .setJobId(jobId)
                            .setStatus(com.sc.lcm.core.grpc.JobStatus.COMPLETED)
                            .setMessage("Mock completed")
                            .setExitCode(0)
                            .build())
                    .build());

            // 7. Verify the status was forwarded to `jobs.status` topic
            ConsumerRecord<String, String> statusRecord = awaitStatusRecord(
                    companion,
                    jobId,
                    Duration.ofSeconds(15));

            assertNotNull(statusRecord, "Should have received a status message on jobs.status topic");
            assertTrue(statusRecord.value().contains("COMPLETED"), "Status message should contain COMPLETED");
            assertTrue(statusRecord.value().contains(jobId), "Status message should contain the job ID");

            JobStatusResponse completedStatus = awaitJobStatus(token, jobId, status ->
                    "COMPLETED".equals(status.status()) && Integer.valueOf(0).equals(status.exitCode()),
                    Duration.ofSeconds(15));
            assertEquals("COMPLETED", completedStatus.status(), "Job should be marked completed after callback");
            assertEquals(0, completedStatus.exitCode(), "Completed job should carry the callback exit code");
            assertNotNull(completedStatus.completedAt(), "Completed job should record its completion time");

            streamEmitter.complete();
            streamSubscription.cancel();
        }
    }

    private JobStatusResponse awaitJobStatus(String token, String jobId,
            Predicate<JobStatusResponse> condition,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        JobStatusResponse latestStatus = null;

        while (System.nanoTime() < deadline) {
            latestStatus = given()
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/jobs/{id}/status", jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .as(JobStatusResponse.class);

            if (condition.test(latestStatus)) {
                return latestStatus;
            }

            Thread.sleep(250);
        }

        String latest = latestStatus == null ? "null"
                : latestStatus.status() + "@" + latestStatus.assignedNodeId() + "/" + latestStatus.exitCode();
        fail("Timed out waiting for job status transition for " + jobId + ", latest=" + latest);
        return latestStatus;
    }

    private ConsumerRecord<String, String> awaitStatusRecord(KafkaCompanion companion, String jobId, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();

        try (var statusTask = companion.consumeStrings().fromTopics("jobs.status")) {
            int inspectedRecords = 0;

            while (System.nanoTime() < deadline) {
                try {
                    statusTask.awaitNextRecord(Duration.ofMillis(500));
                } catch (AssertionError ignored) {
                    // Keep polling until timeout so unrelated quiet periods do not fail the test early.
                }

                var records = statusTask.getRecords();
                for (int i = inspectedRecords; i < records.size(); i++) {
                    ConsumerRecord<String, String> record = records.get(i);
                    if (record.value() != null && record.value().contains(jobId)) {
                        return record;
                    }
                }
                inspectedRecords = records.size();
                Thread.sleep(100);
            }
        }

        fail("Timed out waiting for Kafka status message for job " + jobId);
        return null;
    }
}
