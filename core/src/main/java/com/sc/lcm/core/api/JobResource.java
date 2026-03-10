package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.service.SchedulingService;
import com.sc.lcm.core.service.PartitionedSchedulingService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
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

        /**
         * 提交新作业
         */
        @POST
        public Uni<Response> submitJob(JobRequest request) {
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
                job.setStatus(JobStatus.PENDING);
                Job schedulingJob = copyJob(job);

                log.info("📝 Submitting new job: {} ({})", request.name(), jobId);

                return Panache.withTransaction(job::persist)
                                .replaceWith(Response.status(Response.Status.CREATED)
                                                .entity(new JobResponse(jobId, JobStatus.PENDING.name(),
                                                                "Job submitted successfully"))
                                                .build())
                                .invoke(() -> vertx.getDelegate().runOnContext(ignored -> triggerScheduling(jobId, schedulingJob)));
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
                copy.setStatus(original.getStatus());
                return copy;
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
                        String tenantId) {
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
