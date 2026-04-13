package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.ExecutionType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
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

    /**
     * Consumes scheduled jobs from Kafka and dispatches them to Satellites via
     * gRPC.
     */
    @Incoming("job-queue-in")
    public void dispatch(Job job) {
        String nodeId = resolveTargetNodeId(job);
        if (job == null || nodeId == null) {
            LOG.warn("Ignoring invalid job from Kafka because no assigned node information was present");
            return;
        }

        LOG.infof("📨 [Kafka -> Dispatcher] Processing Job %s for Node %s", job.getId(), nodeId);

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
