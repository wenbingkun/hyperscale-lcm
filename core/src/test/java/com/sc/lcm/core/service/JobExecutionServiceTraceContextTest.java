package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.JobStatusCallback;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobExecutionServiceTraceContextTest {

    private JobExecutionService service;

    @BeforeEach
    void setUp() {
        service = new JobExecutionService();
        service.openTelemetry = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Test
    void extractTraceContextRestoresRemoteParentSpan() {
        JobStatusCallback callback = new JobStatusCallback(
                "job-otel",
                "node-otel",
                "COMPLETED",
                0,
                "ok",
                "",
                "",
                0L,
                LocalDateTime.parse("2026-04-08T18:20:00"),
                Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

        Context extractedContext = service.extractTraceContext(callback);
        SpanContext spanContext = Span.fromContext(extractedContext).getSpanContext();

        assertTrue(spanContext.isValid());
        assertTrue(spanContext.isRemote());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", spanContext.getTraceId());
        assertEquals("00f067aa0ba902b7", spanContext.getSpanId());
    }
}
