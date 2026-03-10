package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Node;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JobDispatcherTest {

    private StreamRegistry streamRegistry;
    private JobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        streamRegistry = Mockito.mock(StreamRegistry.class);

        dispatcher = new JobDispatcher();
        dispatcher.streamRegistry = streamRegistry;
        dispatcher.openTelemetry = GlobalOpenTelemetry.get();
    }

    @Test
    void dispatchUsesJobIdAsCommandId() {
        Job job = new Job("job-123", 4, 8, 1, "A100");
        job.setAssignedNode(new Node("node-1", 32, 128, 4, "A100"));

        dispatcher.dispatch(job);

        verify(streamRegistry).sendCommand(
                eq("node-1"),
                eq("job-123"),
                eq("EXEC_DOCKER"),
                eq("hello-world"),
                anyMap());
    }

    @Test
    void dispatchSkipsInvalidJob() {
        dispatcher.dispatch(new Job("job-456", 1, 1, 0, null));

        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                anyMap());
    }
}
