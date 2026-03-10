package com.sc.lcm.core;

import com.sc.lcm.core.api.JobResource.JobRequest;
import com.sc.lcm.core.grpc.HeartbeatRequest;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.StreamRequest;
import com.sc.lcm.core.grpc.JobStatusUpdate;
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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test that exercises the full job lifecycle.
 *
 * Infrastructure dependencies (PostgreSQL, Kafka, Redis) are started
 * automatically by Quarkus DevServices backed by Testcontainers.
 * Ensure Docker is available on the test host.
 */
@QuarkusTest
public class E2EIntegrationTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
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
            ConsumerRecord<String, String> statusRecord = companion.consumeStrings()
                    .fromTopics("jobs.status", 1)
                    .awaitCompletion(Duration.ofSeconds(15))
                    .getFirstRecord();

            assertNotNull(statusRecord, "Should have received a status message on jobs.status topic");
            assertTrue(statusRecord.value().contains("COMPLETED"), "Status message should contain COMPLETED");

            streamEmitter.complete();
            streamSubscription.cancel();
        }
    }
}
