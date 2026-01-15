package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JobDispatcher {

    private static final Logger LOG = Logger.getLogger(JobDispatcher.class);

    @Inject
    StreamRegistry streamRegistry;

    /**
     * Consumes scheduled jobs from Kafka and dispatches them to Satellites via
     * gRPC.
     */
    @Incoming("job-queue-in")
    public void dispatch(Job job) {
        if (job == null || job.getAssignedNode() == null) {
            LOG.warn("Received invalid job from Kafka");
            return;
        }

        String nodeId = job.getAssignedNode().getId();
        LOG.infof("📨 [Kafka -> Dispatcher] Processing Job %s for Node %s", job.getId(), nodeId);

        // In a real app, Payload would be derived from Job spec
        // For Phase 7, we stick to "hello-world" or use job.Name if available
        String payload = "hello-world";

        streamRegistry.sendCommand(nodeId, "EXEC_DOCKER", payload);
    }
}
