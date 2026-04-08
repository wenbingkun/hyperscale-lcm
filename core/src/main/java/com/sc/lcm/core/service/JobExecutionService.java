package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.api.DashboardWebSocket;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.JobExecutionMessage;
import com.sc.lcm.core.domain.JobStatusCallback;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 作业执行服务 (P6-1, P6-3)
 * 
 * 负责：
 * - 发送作业执行消息到 Kafka
 * - 处理 Satellite 执行结果回调
 */
@ApplicationScoped
@Slf4j
public class JobExecutionService {

    private record ScheduledDispatchSnapshot(String jobId, String jobName, String tenantId, String assignedNodeId) {
    }

    private record JobStatusSnapshot(String jobId, String jobName, String assignedNodeId, String status,
            Integer exitCode) {
    }

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @Channel("job-execution-out")
    Emitter<String> jobExecutionEmitter;

    @Inject
    @Channel("job-status-dlq-out")
    Emitter<String> dlqEmitter;

    @Inject
    DashboardWebSocket dashboardWebSocket;

    @Inject
    MetricsService metricsService;

    @Inject
    AuditService auditService;

    @Inject
    OpenTelemetry openTelemetry;

    private static final TextMapGetter<Map<String, String>> TRACE_CONTEXT_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    /**
     * 派发作业到指定节点执行
     */
    public Uni<Void> dispatchJob(Job job, String dockerImage, String command) {
        JobExecutionMessage message = JobExecutionMessage.fromJob(job, dockerImage, command);

        try {
            String json = objectMapper.writeValueAsString(message);
            jobExecutionEmitter.send(json);

            log.info("📤 Job {} dispatched to node {} via Kafka", job.getId(), job.getAssignedNodeId());

            return recordScheduledDispatch(job.getId(), job.getAssignedNodeId());

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize job execution message", e);
            return Uni.createFrom().failure(e);
        }
    }

    /**
     * 记录作业已进入调度队列，避免 UI / API 仍停留在 PENDING。
     */
    public Uni<Void> recordScheduledDispatch(String jobId, String assignedNodeId) {
        return persistScheduledDispatch(jobId, assignedNodeId);
    }

    public void recordScheduledDispatchBlocking(String jobId, String assignedNodeId) {
        try {
            VertxContextSupport.subscribeAndAwait(() -> persistScheduledDispatch(jobId, assignedNodeId));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to persist scheduled state for job " + jobId, t);
        }
    }

    private Uni<Void> persistScheduledDispatch(String jobId, String assignedNodeId) {
        return Panache.withTransaction(() -> Job.<Job>findById(jobId)
                .onItem().transform(job -> {
                    if (job == null) {
                        log.warn("Cannot mark job {} as scheduled because it does not exist", jobId);
                        return null;
                    }

                    if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.SCHEDULED) {
                        log.debug("Skipping schedule update for job {} because status is {}", jobId, job.getStatus());
                        return null;
                    }

                    boolean updated = false;
                    if (job.getStatus() == JobStatus.PENDING) {
                        job.setStatus(JobStatus.SCHEDULED);
                        updated = true;
                    }
                    if (assignedNodeId != null
                            && !assignedNodeId.isBlank()
                            && !assignedNodeId.equals(job.getAssignedNodeId())) {
                        job.setAssignedNodeId(assignedNodeId);
                        updated = true;
                    }
                    if (job.getScheduledAt() == null) {
                        job.setScheduledAt(LocalDateTime.now());
                        updated = true;
                    }

                    if (!updated) {
                        return null;
                    }

                    return new ScheduledDispatchSnapshot(
                            job.getId(),
                            job.getName(),
                            job.getTenantId(),
                            job.getAssignedNodeId());
                }))
                .invoke(snapshot -> {
                    if (snapshot == null) {
                        return;
                    }

                    dashboardWebSocket.broadcastScheduleEvent(
                            snapshot.jobId(),
                            snapshot.assignedNodeId(),
                            "DISPATCHED");
                    dashboardWebSocket.broadcastJobStatus(
                            snapshot.jobId(),
                            snapshot.jobName(),
                            JobStatus.SCHEDULED.name(),
                            snapshot.assignedNodeId(),
                            null);
                    auditService.logJobScheduled(
                            snapshot.jobId(),
                            "SYSTEM",
                            snapshot.tenantId(),
                            snapshot.assignedNodeId())
                            .subscribe().with(v -> {
                            }, e -> log.error("Audit log failed", e));
                })
                .replaceWithVoid();
    }

    /**
     * 处理作业状态回调（来自 Kafka）
     */
    @Incoming("job-status-in")
    public Uni<Void> handleJobStatusCallback(String message) {
        JobStatusCallback callback;
        try {
            callback = objectMapper.readValue(message, JobStatusCallback.class);
        } catch (Exception e) {
            log.error("Failed to parse job status callback, sending to DLQ", e);
            dlqEmitter.send(message);
            return Uni.createFrom().voidItem();
        }

        JobStatus callbackStatus;
        try {
            callbackStatus = JobStatus.valueOf(callback.status());
        } catch (IllegalArgumentException e) {
            log.error("Invalid job status callback {}, sending to DLQ", callback.status(), e);
            dlqEmitter.send(message);
            return Uni.createFrom().voidItem();
        }

        log.info("Received status callback for job {}: {}", callback.jobId(), callback.status());

        JobStatus finalCallbackStatus = callbackStatus;
        Context extractedContext = extractTraceContext(callback);
        Span callbackSpan = openTelemetry.getTracer("hyperscale-lcm-core")
                .spanBuilder("job-status-callback")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(extractedContext)
                .setAttribute("job.id", callback.jobId())
                .setAttribute("node.id", callback.nodeId() == null ? "unknown" : callback.nodeId())
                .setAttribute("job.status", callback.status())
                .startSpan();

        // Attach the span to each async stage instead of using try-with-resources,
        // because the Scope would close immediately after return while the Uni chain
        // continues executing asynchronously.
        return Panache.withTransaction(() -> {
                    Scope scope = callbackSpan.makeCurrent();
                    return Job.<Job>findById(callback.jobId())
                            .onItem().transformToUni(job -> {
                                if (job == null) {
                                    log.warn("Job not found: {}, routing original message to DLQ",
                                            callback.jobId());
                                    dlqEmitter.send(message);
                                    return Uni.createFrom().nullItem();
                                }

                                // 更新作业状态
                                job.setStatus(finalCallbackStatus);
                                if (callback.nodeId() != null && !callback.nodeId().isBlank()) {
                                    job.setAssignedNodeId(callback.nodeId());
                                }
                                job.setExitCode(callback.exitCode());
                                job.setErrorMessage(callback.errorMessage());
                                job.setCompletedAt(callback.completedAt());

                                return Uni.createFrom().item(new JobStatusSnapshot(
                                        job.getId(),
                                        job.getName(),
                                        job.getAssignedNodeId(),
                                        job.getStatus().name(),
                                        job.getExitCode()));
                            })
                            .onTermination().invoke(scope::close);
                }).invoke(snapshot -> {
                    if (snapshot == null) {
                        return;
                    }

                    try (Scope ignored = callbackSpan.makeCurrent()) {
                        // 更新指标
                        if ("COMPLETED".equals(snapshot.status())) {
                            metricsService.recordJobCompleted();
                        } else if ("FAILED".equals(snapshot.status())) {
                            metricsService.recordJobFailed();
                        }

                        // WebSocket 通知
                        dashboardWebSocket.broadcastScheduleEvent(
                                snapshot.jobId(),
                                snapshot.assignedNodeId(),
                                snapshot.status());
                        dashboardWebSocket.broadcastJobStatus(
                                snapshot.jobId(),
                                snapshot.jobName(),
                                snapshot.status(),
                                snapshot.assignedNodeId(),
                                snapshot.exitCode());

                        // 审计日志
                        if ("COMPLETED".equals(snapshot.status())) {
                            auditService.logJobCompleted(snapshot.jobId(), "SYSTEM", null, callback.exitCode())
                                    .subscribe().with(v -> {
                                    }, e -> log.error("Audit log failed", e));
                        } else if ("FAILED".equals(snapshot.status())) {
                            auditService.logJobFailed(snapshot.jobId(), "SYSTEM", null, callback.errorMessage())
                                    .subscribe().with(v -> {
                                    }, e -> log.error("Audit log failed", e));
                        }
                    }
                }).onFailure().invoke(err -> {
                    log.error("Database or business logic failure processing callback, routing to DLQ", err);
                    dlqEmitter.send(message);
                }).onFailure().recoverWithNull()
                .replaceWithVoid()
                .onTermination().invoke(() -> callbackSpan.end());
    }

    Context extractTraceContext(JobStatusCallback callback) {
        if (callback == null || callback.traceContext() == null || callback.traceContext().isEmpty()) {
            return Context.current();
        }
        return openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), callback.traceContext(), TRACE_CONTEXT_GETTER);
    }
}
