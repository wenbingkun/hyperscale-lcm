package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.ExecutionType;
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
    void dispatchUsesCustomDockerImageWhenProvided() {
        Job job = new Job("job-docker", 4, 8, 1, "A100");
        job.setAssignedNode(new Node("node-2", 32, 128, 4, "A100"));
        job.setExecutionType(ExecutionType.DOCKER);
        job.setExecutionPayload("nvcr.io/nvidia/pytorch:24.01-py3");

        dispatcher.dispatch(job);

        verify(streamRegistry).sendCommand(
                eq("node-2"),
                eq("job-docker"),
                eq("EXEC_DOCKER"),
                eq("nvcr.io/nvidia/pytorch:24.01-py3"),
                anyMap());
    }

    @Test
    void dispatchRoutesShellJobsToShellCommands() {
        Job job = new Job("job-shell", 2, 4, 0, null);
        job.setAssignedNode(new Node("node-3", 16, 64, 0, null));
        job.setExecutionType(ExecutionType.SHELL);
        job.setExecutionPayload("echo shell dispatch");

        dispatcher.dispatch(job);

        verify(streamRegistry).sendCommand(
                eq("node-3"),
                eq("job-shell"),
                eq("EXEC_SHELL"),
                eq("echo shell dispatch"),
                anyMap());
    }

    @Test
    void dispatchRoutesSshJobsToSshCommands() {
        Job job = new Job("job-ssh", 2, 4, 0, null);
        job.setAssignedNode(new Node("node-4", 16, 64, 0, null));
        job.setExecutionType(ExecutionType.SSH);
        job.setExecutionPayload("{\"host\":\"10.0.0.8\",\"user\":\"root\",\"password\":\"secret\",\"command\":\"hostname\"}");

        dispatcher.dispatch(job);

        verify(streamRegistry).sendCommand(
                eq("node-4"),
                eq("job-ssh"),
                eq("EXEC_SSH"),
                eq("{\"host\":\"10.0.0.8\",\"user\":\"root\",\"password\":\"secret\",\"command\":\"hostname\"}"),
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
