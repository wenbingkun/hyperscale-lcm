package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.JobStatusCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
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
    OpenTelemetry openTelemetry;

    @Inject
    ObjectMapper objectMapper;

    public void forwardStatus(String jobId, String satelliteId, String statusName, int exitCode, String message,
            Map<String, String> traceContextMap) {
        try {
            JobStatusCallback callback = new JobStatusCallback(
                    jobId, satelliteId, statusName, exitCode, message,
                    "", "", 0L, LocalDateTime.now());
            String json = objectMapper.writeValueAsString(callback);

            if (traceContextMap != null && !traceContextMap.isEmpty()) {
                Context extractedCtx = openTelemetry.getPropagators().getTextMapPropagator()
                        .extract(Context.current(), traceContextMap, new TextMapGetter<Map<String, String>>() {
                            @Override
                            public Iterable<String> keys(Map<String, String> carrier) {
                                return carrier.keySet();
                            }

                            @Override
                            public String get(Map<String, String> carrier, String key) {
                                return carrier.get(key);
                            }
                        });

                try (Scope scope = extractedCtx.makeCurrent()) {
                    jobStatusEmitter.send(json);
                }
            } else {
                jobStatusEmitter.send(json);
            }
        } catch (Exception e) {
            LOG.error("Failed to forward status to Kafka", e);
        }
    }
}
