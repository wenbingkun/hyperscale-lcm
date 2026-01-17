package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Tenant;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 租户管理 REST API
 */
@Path("/api/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ADMIN")
@Slf4j
public class TenantResource {

    /**
     * 获取所有租户
     */
    @GET
    public Uni<List<Tenant>> listTenants() {
        return Tenant.listAll();
    }

    /**
     * 获取活跃租户
     */
    @GET
    @Path("/active")
    public Uni<List<Tenant>> listActiveTenants() {
        return Tenant.findActive();
    }

    /**
     * 获取单个租户
     */
    @GET
    @Path("/{id}")
    public Uni<Response> getTenant(@PathParam("id") String id) {
        return Tenant.findByIdUni(id)
                .map(tenant -> {
                    if (tenant == null) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Tenant not found"))
                                .build();
                    }
                    return Response.ok(tenant).build();
                });
    }

    /**
     * 创建租户
     */
    @POST
    public Uni<Response> createTenant(CreateTenantRequest request) {
        return Tenant.findByIdUni(request.id())
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.CONFLICT)
                                        .entity(new ErrorResponse("Tenant already exists"))
                                        .build());
                    }

                    Tenant tenant = new Tenant(request.id(), request.name());
                    tenant.setDescription(request.description());
                    tenant.setCpuQuota(request.cpuQuota());
                    tenant.setMemoryQuotaGb(request.memoryQuotaGb());
                    tenant.setGpuQuota(request.gpuQuota());
                    tenant.setMaxConcurrentJobs(request.maxConcurrentJobs());

                    log.info("🏢 Creating tenant: {} ({})", request.name(), request.id());

                    return Panache.withTransaction(tenant::persist)
                            .map(t -> Response.status(Response.Status.CREATED)
                                    .entity(tenant)
                                    .build());
                });
    }

    /**
     * 更新租户配额
     */
    @PUT
    @Path("/{id}/quota")
    public Uni<Response> updateQuota(@PathParam("id") String id, UpdateQuotaRequest request) {
        return Panache.withTransaction(() -> Tenant.<Tenant>findById(id)
                .onItem().transformToUni(tenant -> {
                    if (tenant == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Tenant not found"))
                                        .build());
                    }

                    if (request.cpuQuota() != null)
                        tenant.setCpuQuota(request.cpuQuota());
                    if (request.memoryQuotaGb() != null)
                        tenant.setMemoryQuotaGb(request.memoryQuotaGb());
                    if (request.gpuQuota() != null)
                        tenant.setGpuQuota(request.gpuQuota());
                    if (request.maxConcurrentJobs() != null)
                        tenant.setMaxConcurrentJobs(request.maxConcurrentJobs());

                    log.info("📊 Updated quota for tenant: {}", id);

                    return Uni.createFrom().item(Response.ok(tenant).build());
                }));
    }

    /**
     * 获取租户使用统计
     */
    @GET
    @Path("/{id}/usage")
    @RolesAllowed({ "ADMIN", "OPERATOR" })
    public Uni<Response> getUsage(@PathParam("id") String id) {
        return Tenant.findByIdUni(id)
                .map(tenant -> {
                    if (tenant == null) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Tenant not found"))
                                .build();
                    }
                    return Response.ok(new UsageResponse(
                            tenant.getCpuUsed(),
                            tenant.getCpuQuota(),
                            tenant.getCpuUtilization(),
                            tenant.getGpuUsed(),
                            tenant.getGpuQuota(),
                            tenant.getGpuUtilization(),
                            tenant.getMemoryUsedGb(),
                            tenant.getMemoryQuotaGb(),
                            tenant.getRunningJobs(),
                            tenant.getMaxConcurrentJobs())).build();
                });
    }

    /**
     * 暂停租户
     */
    @POST
    @Path("/{id}/suspend")
    public Uni<Response> suspendTenant(@PathParam("id") String id) {
        return Panache.withTransaction(() -> Tenant.<Tenant>findById(id)
                .onItem().transformToUni(tenant -> {
                    if (tenant == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Tenant not found"))
                                        .build());
                    }

                    tenant.setStatus(Tenant.TenantStatus.SUSPENDED);
                    log.info("⏸️ Tenant suspended: {}", id);

                    return Uni.createFrom().item(Response.ok(tenant).build());
                }));
    }

    // ============== DTOs ==============

    public record CreateTenantRequest(
            String id,
            String name,
            String description,
            int cpuQuota,
            long memoryQuotaGb,
            int gpuQuota,
            int maxConcurrentJobs) {
    }

    public record UpdateQuotaRequest(
            Integer cpuQuota,
            Long memoryQuotaGb,
            Integer gpuQuota,
            Integer maxConcurrentJobs) {
    }

    public record UsageResponse(
            int cpuUsed,
            int cpuQuota,
            double cpuUtilization,
            int gpuUsed,
            int gpuQuota,
            double gpuUtilization,
            long memoryUsedGb,
            long memoryQuotaGb,
            int runningJobs,
            int maxConcurrentJobs) {
    }

    public record ErrorResponse(String error) {
    }
}
