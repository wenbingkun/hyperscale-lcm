package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.service.SchedulingService;
import com.sc.lcm.core.service.PartitionedSchedulingService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * 独立的调度与资源分配 API (Sprint 2 - 调度 API 独立化)
 * 
 * 用于与其他外部系统集成，纯粹进行资源分配而不强绑定到具体的任务执行。
 */
@Path("/api/v1/allocations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR", "USER" })
@Slf4j
public class AllocationResource {

        @Inject
        SchedulingService schedulingService;

        @Inject
        PartitionedSchedulingService partitionedSchedulingService;

        @ConfigProperty(name = "lcm.scheduling.partitioned.enabled", defaultValue = "false")
        boolean partitionedSchedulingEnabled;

        /**
         * 提交资源分配请求
         * 返回一个 Allocation ID，用于后续查询调度结果
         */
        @POST
        public Uni<Response> requestAllocation(AllocationRequest request) {
                String allocationId = UUID.randomUUID().toString();

                // 复用 Job 实体作为底层的调度对象，未来可以抽象出单独的 Allocation 实体
                Job allocationJob = new Job();
                allocationJob.setId(allocationId);
                allocationJob.setName("Allocation-" + request.tenantId());
                allocationJob.setDescription("Independent allocation request");
                allocationJob.setRequiredCpuCores(request.cpuCores());
                allocationJob.setRequiredMemoryGb(request.memoryGb());
                allocationJob.setRequiredGpuCount(request.gpuCount());
                allocationJob.setRequiredGpuModel(request.gpuModel());
                allocationJob.setRequiresNvlink(request.requiresNvlink());
                allocationJob.setMinNvlinkBandwidthGbps(request.minNvlinkBandwidthGbps());
                allocationJob.setTenantId(request.tenantId());
                allocationJob.setStatus(JobStatus.PENDING);

                log.info("📊 New allocation requested: {} CPUs, {} GPUs for tenant {}",
                                request.cpuCores(), request.gpuCount(), request.tenantId());

                return Panache.withTransaction(allocationJob::persist)
                                .chain(() -> {
                                        // 异步触发调度引擎
                                        if (partitionedSchedulingEnabled) {
                                                log.info("🚀 Allocation {} partitioned scheduling started",
                                                                allocationId);
                                                partitionedSchedulingService.scheduleByZone(allocationJob).subscribe()
                                                                .with(
                                                                                v -> log.info("✅ Allocation {} partitioned solving completed",
                                                                                                allocationId),
                                                                                e -> log.error("❌ Allocation {} scheduling failed: {}",
                                                                                                allocationId,
                                                                                                e.getMessage()));
                                        } else {
                                                schedulingService.scheduleJob(allocationJob).subscribe().with(
                                                                v -> log.info("🚀 Allocation {} scheduling started",
                                                                                allocationId),
                                                                e -> log.error("❌ Allocation {} scheduling failed: {}",
                                                                                allocationId, e.getMessage()));
                                        }

                                        return Uni.createFrom().item(Response.status(Response.Status.ACCEPTED)
                                                        .entity(new AllocationResponse(
                                                                        allocationId,
                                                                        "PENDING",
                                                                        "Allocation requested and queued for scheduling"))
                                                        .build());
                                });
        }

        /**
         * 查询分配结果
         */
        @GET
        @Path("/{id}")
        public Uni<Response> getAllocationStatus(@PathParam("id") String id) {
                return Job.findByIdReactive(id)
                                .onItem().transform(job -> {
                                        if (job == null) {
                                                return Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse(
                                                                                "Allocation not found: " + id))
                                                                .build();
                                        }

                                        return Response.ok(new AllocationResultResponse(
                                                        job.getId(),
                                                        job.getStatus().name(),
                                                        job.getAssignedNodeId(),
                                                        job.getScheduledAt() != null ? job.getScheduledAt().toString()
                                                                        : null))
                                                        .build();
                                });
        }

        // ============== DTO Records ==============

        public record AllocationRequest(
                        int cpuCores,
                        long memoryGb,
                        int gpuCount,
                        String gpuModel,
                        boolean requiresNvlink,
                        int minNvlinkBandwidthGbps,
                        String tenantId) {
        }

        public record AllocationResponse(
                        String allocationId,
                        String status,
                        String message) {
        }

        public record AllocationResultResponse(
                        String allocationId,
                        String status,
                        String assignedNodeId,
                        String allocatedAt) {
        }

        public record ErrorResponse(String error) {
        }
}
