package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.JobExecutionMessage;
import com.sc.lcm.core.domain.JobStatusCallback;
import io.smallrye.mutiny.Uni;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobExecutionServiceTest {

    private Emitter<String> jobExecutionEmitter;
    private Emitter<String> dlqEmitter;
    private TestJobExecutionService service;

    @BeforeEach
    void setUp() {
        jobExecutionEmitter = Mockito.mock(Emitter.class);
        dlqEmitter = Mockito.mock(Emitter.class);
        Mockito.when(jobExecutionEmitter.send(Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(dlqEmitter.send(Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));

        service = new TestJobExecutionService();
        service.objectMapper = new ObjectMapper();
        service.jobExecutionEmitter = jobExecutionEmitter;
        service.dlqEmitter = dlqEmitter;
    }

    @Test
    void dispatchJobSerializesPayloadAndRecordsScheduledDispatch() throws Exception {
        Job job = new Job("job-123", 16, 256, 4, "H100");
        job.setAssignedNodeId("node-7");
        job.setPriority(9);

        service.dispatchJob(job, "ghcr.io/sc/job:latest", "python train.py")
                .await().indefinitely();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jobExecutionEmitter).send(payloadCaptor.capture());

        JobExecutionMessage message = new ObjectMapper()
                .readValue(payloadCaptor.getValue(), JobExecutionMessage.class);
        assertEquals("job-123", message.jobId());
        assertEquals("node-7", message.nodeId());
        assertEquals("ghcr.io/sc/job:latest", message.dockerImage());
        assertEquals("python train.py", message.command());
        assertEquals("job-123", service.recordedJobId);
        assertEquals("node-7", service.recordedAssignedNodeId);
    }

    @Test
    void dispatchJobReturnsSerializationFailure() {
        service.objectMapper = Mockito.mock(ObjectMapper.class);
        Job job = new Job("job-500", 8, 64, 1, "A100");
        job.setAssignedNodeId("node-1");

        try {
            Mockito.when(service.objectMapper.writeValueAsString(Mockito.any()))
                    .thenThrow(new JsonProcessingException("boom") {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

        Throwable failure = assertThrows(
                Throwable.class,
                () -> service.dispatchJob(job, "busybox", "echo hi").await().indefinitely());

        assertTrue(failure instanceof JsonProcessingException
                || failure.getCause() instanceof JsonProcessingException);
        Mockito.verifyNoInteractions(jobExecutionEmitter);
    }

    @Test
    void handleJobStatusCallbackRoutesInvalidJsonToDlq() {
        service.handleJobStatusCallback("not-json").await().indefinitely();

        Mockito.verify(dlqEmitter).send("not-json");
    }

    @Test
    void handleJobStatusCallbackRoutesInvalidStatusToDlq() throws Exception {
        String payload = service.objectMapper.writeValueAsString(new JobStatusCallback(
                "job-404",
                "node-x",
                "UNKNOWN",
                1,
                "invalid",
                "",
                "",
                0L,
                null,
                java.util.Map.of()));

        service.handleJobStatusCallback(payload).await().indefinitely();

        Mockito.verify(dlqEmitter).send(payload);
        assertTrue(service.recordedJobId == null);
    }

    private static class TestJobExecutionService extends JobExecutionService {
        private String recordedJobId;
        private String recordedAssignedNodeId;

        @Override
        public Uni<Void> recordScheduledDispatch(String jobId, String assignedNodeId) {
            recordedJobId = jobId;
            recordedAssignedNodeId = assignedNodeId;
            return Uni.createFrom().voidItem();
        }
    }
}
