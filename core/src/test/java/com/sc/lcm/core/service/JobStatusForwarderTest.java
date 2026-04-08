package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sc.lcm.core.domain.JobStatusCallback;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobStatusForwarderTest {

    private Emitter<String> emitter;
    private JobStatusForwarder forwarder;

    @BeforeEach
    void setUp() {
        emitter = Mockito.mock(Emitter.class);
        Mockito.when(emitter.send(Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));

        forwarder = new JobStatusForwarder();
        forwarder.jobStatusEmitter = emitter;
        forwarder.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void forwardStatusEmbedsTraceContextIntoKafkaPayload() throws Exception {
        Map<String, String> traceContext = Map.of(
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "baggage", "tenant=default");

        forwarder.forwardStatus("job-123", "node-1", "RUNNING", 0, "started", traceContext);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(emitter).send(payloadCaptor.capture());

        JobStatusCallback callback = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readValue(payloadCaptor.getValue(), JobStatusCallback.class);
        assertEquals("job-123", callback.jobId());
        assertNotNull(callback.traceContext());
        assertEquals(traceContext, callback.traceContext());
    }
}
