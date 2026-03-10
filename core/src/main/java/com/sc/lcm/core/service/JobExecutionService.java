package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.api.DashboardWebSocket;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.JobExecutionMessage;
import com.sc.lcm.core.domain.JobStatusCallback;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.LocalDateTime;

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

    /**
     * 派发作业到指定节点执行
     */
    public Uni<Void> dispatchJob(Job job, String dockerImage, String command) {
        JobExecutionMessage message = JobExecutionMessage.fromJob(job, dockerImage, command);

        try {
            String json = objectMapper.writeValueAsString(message);
            jobExecutionEmitter.send(json);

            log.info("📤 Job {} dispatched to node {} via Kafka", job.getId(), job.getAssignedNodeId());

            // 更新状态
            return Panache.withTransaction(() -> Job.<Job>findById(job.getId())
                    .onItem().transformToUni(j -> {
                        if (j != null) {
                            j.setAssignedNodeId(job.getAssignedNodeId());
                            j.setStatus(JobStatus.SCHEDULED);
                            j.setScheduledAt(LocalDateTime.now());
                        }
                        return Uni.createFrom().voidItem();
                    })).invoke(() -> {
                        dashboardWebSocket.broadcastScheduleEvent(job.getId(), job.getAssignedNodeId(), "DISPATCHED");
                    });

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize job execution message", e);
            return Uni.createFrom().failure(e);
        }
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

        log.info("Received status callback for job {}: {}", callback.jobId(), callback.status());

        return Panache.withTransaction(() -> Job.<Job>findById(callback.jobId())
                .onItem().transformToUni(job -> {
                    if (job == null) {
                        log.warn("Job not found: {}, routing original message to DLQ", callback.jobId());
                        dlqEmitter.send(message);
                        return Uni.createFrom().voidItem();
                    }

                    // 更新作业状态
                    job.setStatus(JobStatus.valueOf(callback.status()));
                    job.setExitCode(callback.exitCode());
                    job.setErrorMessage(callback.errorMessage());
                    job.setCompletedAt(callback.completedAt());

                    return Uni.createFrom().voidItem();
                })).invoke(() -> {
                    // 更新指标
                    if ("COMPLETED".equals(callback.status())) {
                        metricsService.recordJobCompleted();
                    } else if ("FAILED".equals(callback.status())) {
                        metricsService.recordJobFailed();
                    }

                    // WebSocket 通知
                    dashboardWebSocket.broadcastScheduleEvent(
                            callback.jobId(),
                            callback.nodeId(),
                            callback.status());

                    // 审计日志
                    if ("COMPLETED".equals(callback.status())) {
                        auditService.logJobCompleted(callback.jobId(), "SYSTEM", null, callback.exitCode())
                                .subscribe().with(v -> {
                                }, e -> log.error("Audit log failed", e));
                    } else if ("FAILED".equals(callback.status())) {
                        auditService.logJobFailed(callback.jobId(), "SYSTEM", null, callback.errorMessage())
                                .subscribe().with(v -> {
                                }, e -> log.error("Audit log failed", e));
                    }
                }).onFailure().invoke(err -> {
                    log.error("Database or business logic failure processing callback, routing to DLQ", err);
                    dlqEmitter.send(message);
                }).onFailure().recoverWithNull();
    }
}
