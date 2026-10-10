package com.sc.lcm.core.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.ExecutionType;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.service.AuditService;
import com.sc.lcm.core.service.SchedulingService;
import com.sc.lcm.core.service.PartitionedSchedulingService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 作业管理 REST API (P4-1)
 * 
 * RBAC 权限:
 * - ADMIN, OPERATOR, USER: 提交/查看作业
 * - ADMIN, OPERATOR: 取消作业
 */
@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR", "USER" })
@Slf4j
public class JobResource {

        @Inject
        SchedulingService schedulingService;

        @Inject
        PartitionedSchedulingService partitionedSchedulingService;

        @ConfigProperty(name = "lcm.scheduling.partitioned.enabled", defaultValue = "false")
        boolean partitionedSchedulingEnabled;

        @Inject
        Vertx vertx;

        @Inject
        SecurityIdentity identity;

        @Inject
        AuditService auditService;

        @ConfigProperty(name = "lcm.ssh.inline-payload.enabled", defaultValue = "false")
        boolean inlineSshPayloadEnabled;

        @ConfigProperty(name = "lcm.ssh.default-user")
        Optional<String> sshDefaultUser;

        @ConfigProperty(name = "lcm.ssh.default-key-ref")
        Optional<String> sshDefaultKeyRef;

        @ConfigProperty(name = "lcm.ssh.default-port", defaultValue = "22")
        int sshDefaultPort;

        // Mirrors the fixed task catalog on the Satellite (satellite/pkg/executor/ssh.go).
        private static final Set<String> SSH_TASKS = Set.of("SYSTEM_INFO");
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        /**
         * 提交新作业
         */
        @POST
        public Uni<Response> submitJob(JobRequest request) {
                ExecutionType executionType;
                try {
                        executionType = parseExecutionType(request.executionType());
                } catch (IllegalArgumentException error) {
                        return badRequest(error.getMessage());
                }

                if (executionType == ExecutionType.SSH && hasText(request.task())) {
                        return submitSshTaskJob(request);
                }

                if (executionType == ExecutionType.SSH && !inlineSshPayloadEnabled) {
                        return badRequest("Inline SSH payloads are disabled. Submit an SSH task job with "
                                        + "targetDeviceId and task instead.");
                }
                if (hasText(request.targetDeviceId())) {
                        return badRequest("targetDeviceId is only supported for SSH task jobs");
                }

                String executionPayload;
                try {
                        executionPayload = normalizeExecutionPayload(executionType, request.executionPayload());
                } catch (IllegalArgumentException error) {
                        return badRequest(error.getMessage());
                }

                return persistAndSchedule(request, executionType, executionPayload, null);
        }

        /**
         * SSH 任务作业: 目标必须是已批准设备，命令来自 Satellite 的固定任务目录，
         * payload 由服务端构造且不含任何凭据。
         */
        private Uni<Response> submitSshTaskJob(JobRequest request) {
                if (!SSH_TASKS.contains(request.task())) {
                        return badRequest("Unsupported SSH task: " + request.task());
                }
                if (!hasText(request.targetDeviceId())) {
                        return badRequest("targetDeviceId is required for SSH task jobs");
                }
                if (hasText(request.executionPayload())) {
                        return badRequest("executionPayload must not be set for SSH task jobs");
                }
                if (sshDefaultUser.isEmpty() || sshDefaultKeyRef.isEmpty()) {
                        return Uni.createFrom().item(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                                        .entity(new ErrorResponse("SSH task jobs are not configured "
                                                        + "(lcm.ssh.default-user / lcm.ssh.default-key-ref)"))
                                        .build());
                }

                return DiscoveredDevice.<DiscoveredDevice>findById(request.targetDeviceId())
                                .onItem().transformToUni(device -> {
                                        if (device == null) {
                                                return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Target device not found: "
                                                                                + request.targetDeviceId()))
                                                                .build());
                                        }
                                        DiscoveryStatus status = device.getStatus();
                                        if (status != DiscoveryStatus.APPROVED && status != DiscoveryStatus.MANAGED) {
                                                return badRequest("Target device is not approved (status: " + status + ")");
                                        }
                                        if (!hasText(device.getIpAddress())) {
                                                return badRequest("Target device has no IP address");
                                        }

                                        String payload = buildSshTaskPayload(device.getIpAddress(), request.task());
                                        return persistAndSchedule(request, ExecutionType.SSH, payload, device);
                                });
        }

        private String buildSshTaskPayload(String host, String task) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("host", host);
                payload.put("port", sshDefaultPort);
                payload.put("user", sshDefaultUser.get());
                payload.put("keyRef", sshDefaultKeyRef.get());
                payload.put("task", task);
                try {
                        return OBJECT_MAPPER.writeValueAsString(payload);
                } catch (JsonProcessingException error) {
                        throw new IllegalStateException("Failed to build SSH task payload", error);
                }
        }

        private Uni<Response> persistAndSchedule(JobRequest request, ExecutionType executionType,
                        String executionPayload, DiscoveredDevice target) {
                String jobId = UUID.randomUUID().toString();

                Job job = new Job();
                job.setId(jobId);
                job.setName(request.name());
                job.setDescription(request.description());
                job.setRequiredCpuCores(request.cpuCores());
                job.setRequiredMemoryGb(request.memoryGb());
                job.setRequiredGpuCount(request.gpuCount());
                job.setRequiredGpuModel(request.gpuModel());
                job.setRequiresNvlink(request.requiresNvlink());
                job.setMinNvlinkBandwidthGbps(request.minNvlinkBandwidthGbps());
                job.setTenantId(request.tenantId());
                job.setClusterId(request.clusterId());
                job.setStatus(JobStatus.PENDING);
                job.setExecutionType(executionType);
                job.setExecutionPayload(executionPayload);
                if (target != null) {
                        job.setTargetDeviceId(target.getId());
                        job.setTargetHost(target.getIpAddress());
                }
                Job schedulingJob = copyJob(job);

                String actor = identity.getPrincipal() != null ? identity.getPrincipal().getName() : "unknown";
                Map<String, Object> auditDetails = new LinkedHashMap<>();
                auditDetails.put("action", "submitted");
                auditDetails.put("executionType", executionType.name());
                if (target != null) {
                        auditDetails.put("task", request.task());
                        auditDetails.put("targetDeviceId", target.getId());
                        auditDetails.put("targetHost", target.getIpAddress());
                }
                String auditJson;
                try {
                        auditJson = OBJECT_MAPPER.writeValueAsString(auditDetails);
                } catch (JsonProcessingException error) {
                        auditJson = "{\"action\":\"submitted\"}";
                }
                String auditDetailsJson = auditJson;

                log.info("📝 Submitting new job: {} ({})", request.name(), jobId);

                return Panache.withTransaction(job::persist)
                                .replaceWith(Response.status(Response.Status.CREATED)
                                                .entity(new JobResponse(jobId, JobStatus.PENDING.name(),
                                                                "Job submitted successfully"))
                                                .build())
                                // Audit is written before the response so the record exists when the caller sees
                                // 201; an audit failure is logged but must not fail the submission.
                                .call(() -> auditService.logEvent(AuditEventType.JOB_SUBMITTED, "JOB", jobId, actor,
                                                request.tenantId(), auditDetailsJson)
                                                .onFailure().invoke(e -> log.error("Audit log failed", e))
                                                .onFailure().recoverWithNull())
                                .invoke(() -> vertx.getDelegate()
                                                .runOnContext(ignored -> triggerScheduling(jobId, schedulingJob)));
        }

        private static Uni<Response> badRequest(String message) {
                return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                                .entity(new ErrorResponse(message))
                                .build());
        }

        private static boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

        private void triggerScheduling(String jobId, Job schedulingJob) {
                if (partitionedSchedulingEnabled) {
                        log.info("🚀 Job {} partitioned scheduling started", jobId);
                        partitionedSchedulingService.scheduleByZone(schedulingJob).subscribe().with(
                                        v -> log.info("✅ Job {} partitioned solving completed", jobId),
                                        e -> log.error("❌ Job {} scheduling failed", jobId, e));
                } else {
                        schedulingService.scheduleJob(schedulingJob).subscribe().with(
                                        v -> log.info("🚀 Job {} scheduling started", jobId),
                                        e -> log.error("❌ Job {} scheduling failed", jobId, e));
                }
        }

        private Job copyJob(Job original) {
                Job copy = new Job();
                copy.setId(original.getId());
                copy.setName(original.getName());
                copy.setDescription(original.getDescription());
                copy.setRequiredCpuCores(original.getRequiredCpuCores());
                copy.setRequiredMemoryGb(original.getRequiredMemoryGb());
                copy.setRequiredGpuCount(original.getRequiredGpuCount());
                copy.setRequiredGpuModel(original.getRequiredGpuModel());
                copy.setRequiresNvlink(original.isRequiresNvlink());
                copy.setMinNvlinkBandwidthGbps(original.getMinNvlinkBandwidthGbps());
                copy.setTenantId(original.getTenantId());
                copy.setClusterId(original.getClusterId());
                copy.setStatus(original.getStatus());
                copy.setExecutionType(original.getExecutionType());
                copy.setExecutionPayload(original.getExecutionPayload());
                copy.setTargetDeviceId(original.getTargetDeviceId());
                copy.setTargetHost(original.getTargetHost());
                return copy;
        }

        private ExecutionType parseExecutionType(String requestedExecutionType) {
                if (requestedExecutionType == null || requestedExecutionType.isBlank()) {
                        return ExecutionType.DOCKER;
                }

                try {
                        return ExecutionType.valueOf(requestedExecutionType.trim().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                        throw new IllegalArgumentException("Unsupported executionType: " + requestedExecutionType);
                }
        }

        private String normalizeExecutionPayload(ExecutionType executionType, String requestedExecutionPayload) {
                if (requestedExecutionPayload != null && !requestedExecutionPayload.isBlank()) {
                        return requestedExecutionPayload;
                }

                if (executionType == ExecutionType.DOCKER) {
                        return "hello-world";
                }

                throw new IllegalArgumentException(
                                "executionPayload is required for executionType " + executionType.name());
        }

        /**
         * 列出所有作业
         */
        @GET
        public Uni<List<Job>> listJobs(
                        @QueryParam("status") String status,
                        @QueryParam("tenantId") String tenantId,
                        @QueryParam("limit") @DefaultValue("100") int limit) {

                if (status != null) {
                        return Job.findByStatus(JobStatus.valueOf(status.toUpperCase()));
                }
                if (tenantId != null) {
                        return Job.findByTenant(tenantId);
                }
                return Job.findAll().page(0, limit).list();
        }

        /**
         * 获取单个作业详情
         */
        @GET
        @Path("/{id}")
        public Uni<Response> getJob(@PathParam("id") String id) {
                return Job.findByIdReactive(id)
                                .onItem().transform(job -> {
                                        if (job == null) {
                                                return Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Job not found: " + id))
                                                                .build();
                                        }
                                        return Response.ok(job).build();
                                });
        }

        /**
         * 取消作业 (仅 ADMIN 和 OPERATOR)
         */
        @SuppressWarnings("unused")
        @DELETE
        @Path("/{id}")
        @RolesAllowed({ "ADMIN", "OPERATOR" })
        public Uni<Response> cancelJob(@PathParam("id") String id) {
                return Panache.withTransaction(() -> Job.<Job>findByIdReactive(id)
                                .onItem().transformToUni(job -> {
                                        if (job == null) {
                                                return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Job not found: " + id))
                                                                .build());
                                        }
                                        if (job.getStatus() == JobStatus.COMPLETED
                                                        || job.getStatus() == JobStatus.CANCELLED) {
                                                return Uni.createFrom().item(Response
                                                                .status(Response.Status.BAD_REQUEST)
                                                                .entity(new ErrorResponse(
                                                                                "Cannot cancel job in status: "
                                                                                                + job.getStatus()))
                                                                .build());
                                        }

                                        job.setStatus(JobStatus.CANCELLED);
                                        log.info("🚫 Job {} cancelled", id);

                                        return Uni.createFrom().item(
                                                        Response.ok(new JobResponse(id, "CANCELLED", "Job cancelled"))
                                                                        .build());
                                }));
        }

        /**
         * 获取作业状态
         */
        @GET
        @Path("/{id}/status")
        public Uni<Response> getJobStatus(@PathParam("id") String id) {
                return Job.findByIdReactive(id)
                                .onItem().transform(job -> {
                                        if (job == null) {
                                                return Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Job not found: " + id))
                                                                .build();
                                        }
                                        return Response.ok(new JobStatusResponse(
                                                        job.getId(),
                                                        job.getStatus().name(),
                                                        job.getAssignedNodeId(),
                                                        job.getScheduledAt(),
                                                        job.getCompletedAt(),
                                                        job.getExitCode())).build();
                                });
        }

        /**
         * 获取作业统计
         */
        @GET
        @Path("/stats")
        public Uni<JobStats> getJobStats() {
                // Execute sequentially to avoid concurrent session usage issues
                return Job.countByStatus(JobStatus.PENDING).flatMap(pending -> Job.countByStatus(JobStatus.SCHEDULED)
                                .flatMap(scheduled -> Job.countByStatus(JobStatus.RUNNING).flatMap(running -> Job
                                                .countByStatus(JobStatus.COMPLETED)
                                                .flatMap(completed -> Job.countByStatus(JobStatus.FAILED)
                                                                .map(failed -> new JobStats(pending, scheduled, running,
                                                                                completed, failed))))));
        }

        // ============== DTO Records ==============

        public record JobRequest(
                        String name,
                        String description,
                        int cpuCores,
                        long memoryGb,
                        int gpuCount,
                        String gpuModel,
                        boolean requiresNvlink,
                        int minNvlinkBandwidthGbps,
                        String tenantId,
                        String clusterId,
                        String executionType,
                        String executionPayload,
                        String targetDeviceId,
                        String task) {

                public JobRequest(String name, String description, int cpuCores, long memoryGb, int gpuCount,
                                String gpuModel, boolean requiresNvlink, int minNvlinkBandwidthGbps,
                                String tenantId, String clusterId, String executionType, String executionPayload) {
                        this(name, description, cpuCores, memoryGb, gpuCount, gpuModel, requiresNvlink,
                                        minNvlinkBandwidthGbps, tenantId, clusterId, executionType, executionPayload,
                                        null, null);
                }
        }

        public record JobResponse(String id, String status, String message) {
        }

        public record JobStatusResponse(
                        String id,
                        String status,
                        String assignedNodeId,
                        LocalDateTime scheduledAt,
                        LocalDateTime completedAt,
                        Integer exitCode) {
        }

        public record JobStats(
                        long pending,
                        long scheduled,
                        long running,
                        long completed,
                        long failed) {
        }

        public record ErrorResponse(String error) {
        }
}
