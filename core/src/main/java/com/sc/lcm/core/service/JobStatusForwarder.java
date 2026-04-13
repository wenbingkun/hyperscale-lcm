package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.JobStatusCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Map;

@ApplicationScoped
public class JobStatusForwarder {

    private static final Logger LOG = Logger.getLogger(JobStatusForwarder.class);

    @Inject
    @Channel("job-status-out")
    Emitter<String> jobStatusEmitter;

    @Inject
    ObjectMapper objectMapper;

    public JobStatusCallback forwardStatus(String jobId, String nodeId, String statusName, int exitCode, String message,
            Map<String, String> traceContextMap) {
        try {
            JobStatusCallback callback = new JobStatusCallback(
                    jobId, nodeId, statusName, exitCode, message,
                    "", "", 0L, LocalDateTime.now(),
                    traceContextMap == null || traceContextMap.isEmpty() ? null : Map.copyOf(traceContextMap));
            String json = objectMapper.writeValueAsString(callback);
            jobStatusEmitter.send(json);
            return callback;
        } catch (Exception e) {
            LOG.error("Failed to forward status to Kafka", e);
            return new JobStatusCallback(
                    jobId,
                    nodeId,
                    statusName,
                    exitCode,
                    message,
                    "",
                    "",
                    0L,
                    LocalDateTime.now(),
                    traceContextMap == null || traceContextMap.isEmpty() ? null : Map.copyOf(traceContextMap));
        }
    }
}
