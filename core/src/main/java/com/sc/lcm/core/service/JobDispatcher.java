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
    JobStateReader jobStateReader;

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

        if (job.getExecutionType() == ExecutionType.SSH && job.getTargetDeviceId() != null) {
            // Complete message processing only after verification and any refusal are recorded.
            return sshTargetVerifier.findMismatch(job)
                    .chain(mismatch -> {
                        if (mismatch == null) {
                            send(job, nodeId);
                            return Uni.createFrom().voidItem();
                        }
                        LOG.warnf("Refusing to dispatch SSH job %s: %s", job.getId(), mismatch);
                        return jobExecutionService.processJobStatusCallback(new JobStatusCallback(
                                job.getId(), nodeId, "FAILED", -1, mismatch, null, null, 0,
                                LocalDateTime.now(), null));
                    })
                    .onFailure().invoke(e -> LOG.errorf(e,
                            "Target verification or refusal recording failed for job %s; not dispatching", job.getId()));
        }
        send(job, nodeId);
        return Uni.createFrom().voidItem();
    }

    /** Only jobs still SCHEDULED in the database are sent; a job cancelled while its message was in flight is dropped. */
    private void send(Job job, String nodeId) {
        jobStateReader.isScheduled(job.getId()).subscribe().with(
                scheduled -> {
                    if (scheduled) {
                        doSend(job, nodeId);
                    } else {
                        LOG.warnf("Not dispatching job %s: it is no longer SCHEDULED", job.getId());
                    }
                },
                e -> LOG.errorf(e, "Could not confirm state of job %s; not dispatching", job.getId()));
    }

    private void doSend(Job job, String nodeId) {
        ExecutionCommand executionCommand = resolveExecutionCommand(job);

        // Extract Context
        Map<String, String> traceContext = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), traceContext, Map::put);

        streamRegistry.sendCommand(
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
