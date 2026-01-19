package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.ScanJob;
import com.sc.lcm.core.domain.ScanJob.ScanStatus;
import com.sc.lcm.core.service.NetworkScanService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 网络扫描 REST API
 */
@Path("/api/scan")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR" })
@Slf4j
public class NetworkScanResource {

    @Inject
    NetworkScanService scanService;

    @Inject
    SecurityIdentity identity;

    /**
     * 获取最近的扫描任务
     */
    @GET
    public Uni<List<ScanJob>> listScans(@QueryParam("limit") @DefaultValue("20") int limit) {
        return ScanJob.findRecent(limit);
    }

    /**
     * 获取单个扫描任务详情
     */
    @GET
    @Path("/{id}")
    public Uni<Response> getScan(@PathParam("id") String id) {
        return ScanJob.<ScanJob>findById(id)
                .map(job -> {
                    if (job == null) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Scan job not found"))
                                .build();
                    }
                    return Response.ok(job).build();
                });
    }

    /**
     * 启动新的网络扫描
     */
    @POST
    @RolesAllowed("ADMIN")
    public Uni<Response> startScan(StartScanRequest request) {
        // Validate target format
        if (request.target() == null || request.target().isEmpty()) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorResponse("Target is required (CIDR or IP range)"))
                            .build());
        }

        // Check if there's already a running scan
        return ScanJob.findRunning()
                .flatMap(running -> {
                    if (running != null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.CONFLICT)
                                        .entity(new ErrorResponse("A scan is already running"))
                                        .build());
                    }

                    String username = identity.getPrincipal().getName();
                    ScanJob job = new ScanJob(request.target(), username);

                    if (request.ports() != null && !request.ports().isEmpty()) {
                        job.setPorts(request.ports());
                    }
                    if (request.tenantId() != null) {
                        job.setTenantId(request.tenantId());
                    }

                    log.info("🚀 Starting network scan for: {} by {}", request.target(), username);

                    return Panache.withTransaction(job::persist)
                            .chain(() -> scanService.executeScan(job))
                            .map(v -> Response.status(Response.Status.ACCEPTED)
                                    .entity(new ScanResponse(job.getId(), "Scan started"))
                                    .build());
                });
    }

    /**
     * 取消正在运行的扫描
     */
    @POST
    @Path("/{id}/cancel")
    @RolesAllowed("ADMIN")
    public Uni<Response> cancelScan(@PathParam("id") String id) {
        return Panache.withTransaction(() -> ScanJob.<ScanJob>findById(id)
                .onItem().transformToUni(job -> {
                    if (job == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Scan job not found"))
                                        .build());
                    }
                    if (job.getStatus() != ScanStatus.RUNNING) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Scan is not running"))
                                        .build());
                    }

                    job.setStatus(ScanStatus.CANCELLED);
                    scanService.cancelScan();
                    log.info("❌ Scan cancelled: {}", id);

                    return Uni.createFrom().item(
                            Response.ok(new ScanResponse(id, "Scan cancelled")).build());
                }));
    }

    /**
     * 删除扫描任务记录
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Uni<Response> deleteScan(@PathParam("id") String id) {
        return Panache.withTransaction(() -> ScanJob.<ScanJob>findById(id)
                .onItem().transformToUni(job -> {
                    if (job == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Scan job not found"))
                                        .build());
                    }
                    if (job.getStatus() == ScanStatus.RUNNING) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Cannot delete running scan"))
                                        .build());
                    }

                    return job.delete()
                            .map(v -> Response.noContent().build());
                }));
    }

    /**
     * 获取当前运行中的扫描
     */
    @GET
    @Path("/running")
    public Uni<Response> getRunning() {
        return ScanJob.findRunning()
                .map(job -> {
                    if (job == null) {
                        return Response.ok(new RunningResponse(false, null)).build();
                    }
                    return Response.ok(new RunningResponse(true, job)).build();
                });
    }

    // ============== DTOs ==============

    public record StartScanRequest(
            String target,
            String ports,
            String tenantId) {
    }

    public record ScanResponse(String id, String message) {
    }

    public record RunningResponse(boolean running, ScanJob job) {
    }

    public record ErrorResponse(String error) {
    }
}
