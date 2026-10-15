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
    private JobDispatchStore jobStateReader;

    @BeforeEach
    void setUp() {
        streamRegistry = Mockito.mock(StreamRegistry.class);

        dispatcher = new JobDispatcher();
        dispatcher.streamRegistry = streamRegistry;
        dispatcher.openTelemetry = GlobalOpenTelemetry.get();
        jobStateReader = Mockito.mock(JobDispatchStore.class);
        Mockito.when(jobStateReader.claim(Mockito.anyString()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(true));
        Mockito.when(jobStateReader.release(Mockito.anyString()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().voidItem());
        Mockito.when(streamRegistry.isConnected(Mockito.anyString())).thenReturn(true);
        Mockito.when(streamRegistry.sendCommand(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.anyMap())).thenReturn(true);
        dispatcher.dispatchStore = jobStateReader;
    }

    @Test
    void dispatchUsesJobIdAsCommandId() {
        Job job = new Job("job-123", 4, 8, 1, "A100");
        job.setAssignedNode(new Node("node-1", 32, 128, 4, "A100"));

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(streamRegistry).sendCommand(
                eq("node-1"),
                eq("job-123"),
                eq("EXEC_DOCKER"),
                eq("hello-world"),
                anyMap());
    }

    @Test
    void dispatchFallsBackToAssignedNodeIdWhenTransientNodeIsMissing() {
        Job job = new Job("job-node-id", 4, 8, 1, "A100");
        job.setAssignedNodeId("node-from-kafka");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(streamRegistry).sendCommand(
                eq("node-from-kafka"),
                eq("job-node-id"),
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

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

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

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

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

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

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

    @Test
    void dispatchRefusesSshJobWhenTargetChangedAndMarksItFailed() {
        SshTargetVerifier verifier = Mockito.mock(SshTargetVerifier.class);
        JobExecutionService executionService = Mockito.mock(JobExecutionService.class);
        Mockito.when(verifier.findMismatch(Mockito.any()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("Target device address changed since submission"));
        Mockito.when(executionService.processJobStatusCallback(Mockito.any()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().voidItem());
        dispatcher.sshTargetVerifier = verifier;
        dispatcher.jobExecutionService = executionService;

        Job job = new Job("job-ssh-moved", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");
        job.setExecutionType(ExecutionType.SSH);
        job.setTargetDeviceId("dev-1");
        job.setTargetHost("10.0.0.5");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyMap());
        verify(executionService).processJobStatusCallback(Mockito.argThat(cb ->
                "FAILED".equals(cb.status()) && cb.errorMessage().contains("address changed")));
    }

    @Test
    void dispatchSendsSshJobWhenTargetIsUnchanged() {
        SshTargetVerifier verifier = Mockito.mock(SshTargetVerifier.class);
        Mockito.when(verifier.findMismatch(Mockito.any())).thenReturn(io.smallrye.mutiny.Uni.createFrom().nullItem());
        dispatcher.sshTargetVerifier = verifier;

        Job job = new Job("job-ssh-ok", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");
        job.setExecutionType(ExecutionType.SSH);
        job.setExecutionPayload("{\"host\":\"10.0.0.5\"}");
        job.setTargetDeviceId("dev-1");
        job.setTargetHost("10.0.0.5");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(streamRegistry).sendCommand(
                eq("node-1"), eq("job-ssh-ok"), eq("EXEC_SSH"), eq("{\"host\":\"10.0.0.5\"}"), anyMap());
    }
    @Test
    void dispatchCompletionWaitsForTargetVerification() throws Exception {
        SshTargetVerifier verifier = Mockito.mock(SshTargetVerifier.class);
        java.util.concurrent.CompletableFuture<String> verification = new java.util.concurrent.CompletableFuture<>();
        Mockito.when(verifier.findMismatch(Mockito.any()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().completionStage(verification));
        dispatcher.sshTargetVerifier = verifier;
        Job job = new Job("job-delayed-check", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");
        job.setExecutionType(ExecutionType.SSH);
        job.setTargetDeviceId("dev-1");

        var completion = dispatcher.dispatch(job).subscribeAsCompletionStage();
        org.junit.jupiter.api.Assertions.assertFalse(completion.toCompletableFuture().isDone());
        verification.complete(null);
        completion.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(streamRegistry).sendCommand(eq("node-1"), eq(job.getId()), eq("EXEC_SSH"), Mockito.isNull(), anyMap());
    }

    @Test
    void dispatchPropagatesVerificationFailureWithoutSending() {
        SshTargetVerifier verifier = Mockito.mock(SshTargetVerifier.class);
        Mockito.when(verifier.findMismatch(Mockito.any()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("database unavailable")));
        dispatcher.sshTargetVerifier = verifier;
        Job job = new Job("job-check-error", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");
        job.setExecutionType(ExecutionType.SSH);
        job.setTargetDeviceId("dev-1");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5)));
        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), anyMap());
    }


    @Test
    void dispatchDropsJobThatIsNoLongerScheduled() {
        Mockito.when(jobStateReader.claim("job-cancelled"))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(false));
        Job job = new Job("job-cancelled", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyMap());
    }

    @Test
    void dispatchCompletionWaitsForDatabaseStateCheck() throws Exception {
        var state = new java.util.concurrent.CompletableFuture<Boolean>();
        Mockito.when(jobStateReader.claim("job-delayed-state"))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().completionStage(state));
        Job job = new Job("job-delayed-state", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");

        var completion = dispatcher.dispatch(job).subscribeAsCompletionStage().toCompletableFuture();
        org.junit.jupiter.api.Assertions.assertFalse(completion.isDone());
        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyMap());
        state.complete(true);
        completion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(streamRegistry).sendCommand(eq("node-1"), eq(job.getId()), eq("EXEC_DOCKER"), eq("hello-world"), anyMap());
    }

    @Test
    void dispatchPropagatesStateReadFailureWithoutSending() {
        Mockito.when(jobStateReader.claim("job-state-error"))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("database unavailable")));
        Job job = new Job("job-state-error", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5)));
        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyMap());
    }

    @Test
    void dispatchLeavesJobUndeliveredWhenSatelliteHasNoStream() {
        Mockito.when(streamRegistry.isConnected("node-offline")).thenReturn(false);
        Job job = new Job("job-offline", 1, 1, 0, null);
        job.setAssignedNodeId("node-offline");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(jobStateReader, never()).claim(Mockito.anyString());
        verify(streamRegistry, never()).sendCommand(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), anyMap());
    }

    @Test
    void dispatchReleasesTheClaimWhenTheCommandCannotBeEmitted() {
        Mockito.when(streamRegistry.sendCommand(Mockito.eq("node-1"), Mockito.eq("job-emit-fails"),
                Mockito.anyString(), Mockito.any(), Mockito.anyMap())).thenReturn(false);
        Job job = new Job("job-emit-fails", 1, 1, 0, null);
        job.setAssignedNodeId("node-1");

        dispatcher.dispatch(job).await().atMost(java.time.Duration.ofSeconds(5));

        verify(jobStateReader).claim("job-emit-fails");
        verify(jobStateReader).release("job-emit-fails");
    }
}
