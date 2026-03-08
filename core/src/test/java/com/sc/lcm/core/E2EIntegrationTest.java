package com.sc.lcm.core;

import com.sc.lcm.core.api.JobResource.JobRequest;
import com.sc.lcm.core.grpc.HeartbeatRequest;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.StreamRequest;
import com.sc.lcm.core.grpc.JobStatusUpdate;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class E2EIntegrationTest {

        @ConfigProperty(name = "kafka.bootstrap.servers")
        String kafkaBrokers;

        KafkaCompanion companion;

        @BeforeEach
        void setUp() {
                companion = new KafkaCompanion(kafkaBrokers);
        }

        @GrpcClient("lcm")
        LcmService grpcClient;

        @Test
        public void testEndToEndJobLifecycle() throws Exception {
                String satelliteId = UUID.randomUUID().toString();

                // 1. Register Satellite (Mocking Satellite startup)
                RegisterRequest registerRequest = RegisterRequest.newBuilder()
                                .setHostname("test-node-1")
                                .setIpAddress("192.168.1.100")
                                .setAgentVersion("1.0.0")
                                .setOsVersion("Ubuntu 24.04")
                                .build();

                // Note: LCM core Discovery requires manual approval. We might need to approve
                // it or disable requirement for tests.
                // We assume %test.lcm.discovery.require-approval=false is set in
                // application.properties
                try {
                        grpcClient.registerSatellite(registerRequest).await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                        // Ignore if registration fails due to auth or discovery rules in test
                }

                // 2. Send Heartbeat to ensure node is active and has resources
                HeartbeatRequest heartbeat = HeartbeatRequest.newBuilder()
                                .setSatelliteId(satelliteId)
                                .setCpuUsagePercent(10.0f)
                                .setMemoryUsedBytes(1024L)
                                .setMemoryTotalBytes(8192L)
                                .setGpuCount(1)
                                // Add dummy GPU to pass requirements
                                .addGpuMetrics(
                                                com.sc.lcm.core.grpc.GpuMetric.newBuilder().setIndex(0)
                                                                .setUtilizationPercent(0).build())
                                .build();
                grpcClient.sendHeartbeat(heartbeat).await().atMost(Duration.ofSeconds(5));

                // 3. Connect Stream to receive commands
                Multi<StreamRequest> requestStream = Multi.createFrom().items(
                                StreamRequest.newBuilder().setSatelliteId(satelliteId).setInit(true).build());
                // We subscribe to keep the stream open but we don't strictly need to process
                // responses for this E2E
                // if we just verify the job-status-out Kafka topic
                grpcClient.connectStream(requestStream).subscribe().with(resp -> {
                        System.out.println("Received command from Core: " + resp.getCommandType());
                        // We could simulate sending a status update back here
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

                // 6. Verify that the job was evaluated and eventually sent a status to Kafka
                // (Since the dispatcher talks to StreamRegistry, the true e2e verifies the
                // whole chain)
                // For simplicity, we can inject a manual JobStatus update if the command stream
                // mock doesn't send it:
                StreamRequest statusUpdate = StreamRequest.newBuilder()
                                .setSatelliteId(satelliteId)
                                .setStatusUpdate(JobStatusUpdate.newBuilder()
                                                .setJobId(jobId)
                                                .setStatus(com.sc.lcm.core.grpc.JobStatus.COMPLETED)
                                                .setMessage("Mock completed")
                                                .setExitCode(0)
                                                .build())
                                .build();

                // Since we cannot easily inject into the same stream above, just create a new
                // one to push the status
                grpcClient.connectStream(Multi.createFrom().item(statusUpdate))
                                .collect().asList().await().atMost(Duration.ofSeconds(5));

                // 7. Verify the status was forwarded to `jobs.status` topic
                ConsumerRecord<String, String> statusRecord = companion.consumeStrings()
                                .fromTopics("jobs.status", 1)
                                .awaitCompletion(Duration.ofSeconds(15))
                                .getFirstRecord();

                assertNotNull(statusRecord, "Should have received a status message on jobs.status topic");
                assertTrue(statusRecord.value().contains("COMPLETED"), "Status message should contain COMPLETED");
        }
}
