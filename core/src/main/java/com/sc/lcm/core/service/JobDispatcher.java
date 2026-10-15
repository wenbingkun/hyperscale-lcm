package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.ExecutionType;
import com.sc.lcm.core.domain.JobStatusCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.smallrye.mutiny.Uni;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class JobDispatcher {

    private static final Logger LOG = Logger.getLogger(JobDispatcher.class);

    private record ExecutionCommand(String commandType, String payload) {
    }

    @Inject
    StreamRegistry streamRegistry;

    @Inject
    OpenTelemetry openTelemetry;

    @Inject
    SshTargetVerifier sshTargetVerifier;

    @Inject
    JobExecutionService jobExecutionService;

    @Inject
    JobDispatchStore dispatchStore;

    /**
     * Consumes scheduled jobs from Kafka and dispatches them to Satellites via
     * gRPC.
     */
    @Incoming("job-queue-in")
    public Uni<Void> dispatch(Job job) {
        String nodeId = resolveTargetNodeId(job);
        if (job == null || nodeId == null) {
            LOG.warn("Ignoring invalid job from Kafka because no assigned node information was present");
            return Uni.createFrom().voidItem();
        }

        LOG.infof("📨 [Kafka -> Dispatcher] Processing Job %s for Node %s", job.getId(), nodeId);

        return send(job, nodeId);
    }

    /** Only the claim owner may verify the target or emit; duplicates have no lifecycle side effects. */
    private Uni<Void> send(Job job, String nodeId) {
        if (!streamRegistry.isConnected(nodeId)) {
            LOG.warnf("Satellite %s has no command stream; job %s stays SCHEDULED until it is delivered or times out",
                    nodeId, job.getId());
            return Uni.createFrom().voidItem();
        }
        return dispatchStore.claim(job.getId())
                .chain(claimed -> {
                    if (!claimed) {
                        LOG.warnf("Not dispatching job %s: it is no longer SCHEDULED or was already dispatched",
                                job.getId());
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.createFrom().deferred(() ->
                            job.getExecutionType() == ExecutionType.SSH && job.getTargetDeviceId() != null
                                    ? sshTargetVerifier.findMismatch(job)
                                    : Uni.createFrom().<String>nullItem())
                            // No emit has been attempted yet; this failure is safe to release.
                            .onFailure().call(error -> dispatchStore.release(job.getId()))
                            .chain(mismatch -> {
                                if (mismatch != null) {
                                    LOG.warnf("Refusing to dispatch SSH job %s: %s", job.getId(), mismatch);
                                    return jobExecutionService.processJobStatusCallback(new JobStatusCallback(
                                            job.getId(), nodeId, "FAILED", -1, mismatch, null, null, 0,
                                            LocalDateTime.now(), null));
                                }
                                try {
                                    if (doSend(job, nodeId)) {
                                        return Uni.createFrom().voidItem();
                                    }
                                } catch (RuntimeException error) {
                                    LOG.errorf(error, "Emission of job %s is uncertain; retaining its claim", job.getId());
                                    return dispatchStore.recordUncertainSend(job.getId());
                                }
                                // false means there was no emitter, so the command was certainly not handed off.
                                LOG.warnf("No stream for job %s; releasing its claim", job.getId());
                                return dispatchStore.release(job.getId());
                            });
                })
                .onFailure().invoke(error -> LOG.errorf(error, "Dispatch of job %s failed", job.getId()));
    }

    private boolean doSend(Job job, String nodeId) {
        ExecutionCommand executionCommand = resolveExecutionCommand(job);

        // Extract Context
        Map<String, String> traceContext = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), traceContext, Map::put);

        return streamRegistry.sendCommand(
                nodeId,
                job.getId(),
                executionCommand.commandType(),
                executionCommand.payload(),
                traceContext);
    }

    private String resolveTargetNodeId(Job job) {
        if (job == null) {
            return null;
        }

        if (job.getAssignedNodeId() != null && !job.getAssignedNodeId().isBlank()) {
            return job.getAssignedNodeId();
        }

        if (job.getAssignedNode() != null && job.getAssignedNode().getId() != null
                && !job.getAssignedNode().getId().isBlank()) {
            return job.getAssignedNode().getId();
        }

        return null;
    }

    private ExecutionCommand resolveExecutionCommand(Job job) {
        ExecutionType executionType = job.getExecutionType() == null ? ExecutionType.DOCKER : job.getExecutionType();
        String payload = job.getExecutionPayload();

        return switch (executionType) {
            case SHELL -> new ExecutionCommand("EXEC_SHELL", payload);
            case ANSIBLE -> new ExecutionCommand("EXEC_ANSIBLE", payload);
            case SSH -> new ExecutionCommand("EXEC_SSH", payload);
            case DOCKER -> new ExecutionCommand("EXEC_DOCKER",
                    payload == null || payload.isBlank() ? "hello-world" : payload);
        };
    }
}
