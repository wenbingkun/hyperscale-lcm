package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备发现 REST API
 * 
 * 管理待纳管设备池，支持审批和拒绝操作
 */
@Path("/api/discovery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR" })
@Slf4j
public class DiscoveryResource {

    /**
     * 获取所有发现的设备
     */
    @GET
    public Uni<List<DiscoveredDevice>> listDevices(
            @QueryParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit) {

        if (status != null) {
            return DiscoveredDevice.findByStatus(DiscoveryStatus.valueOf(status.toUpperCase()));
        }
        return DiscoveredDevice.findAll().page(0, limit).list();
    }

    /**
     * 获取待审批设备数量
     */
    @GET
    @Path("/pending/count")
    public Uni<CountResponse> getPendingCount() {
        return DiscoveredDevice.countPending()
                .map(count -> new CountResponse(count));
    }

    /**
     * 手动添加设备到发现池
     */
    @POST
    public Uni<Response> addDevice(AddDeviceRequest request) {
        return DiscoveredDevice.findByIp(request.ipAddress())
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.CONFLICT)
                                        .entity(new ErrorResponse("Device already exists: " + request.ipAddress()))
                                        .build());
                    }

                    DiscoveredDevice device = new DiscoveredDevice();
                    device.setIpAddress(request.ipAddress());
                    device.setHostname(request.hostname());
                    device.setMacAddress(request.macAddress());
                    device.setDiscoveryMethod(DiscoveredDevice.DiscoveryMethod.MANUAL);
                    device.setInferredType(request.deviceType());
                    device.setNotes(request.notes());
                    device.setTenantId(request.tenantId());

                    log.info("📡 Adding device manually: {}", request.ipAddress());

                    return Panache.withTransaction(device::persist)
                            .map(d -> Response.status(Response.Status.CREATED)
                                    .entity(device)
                                    .build());
                });
    }

    /**
     * 批准设备纳管
     */
    @POST
    @Path("/{id}/approve")
    @RolesAllowed("ADMIN")
    public Uni<Response> approveDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .onItem().transformToUni(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Device not found"))
                                        .build());
                    }
                    if (device.getStatus() != DiscoveryStatus.PENDING) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Device not in pending status"))
                                        .build());
                    }

                    device.setStatus(DiscoveryStatus.APPROVED);
                    device.setLastProbedAt(LocalDateTime.now());
                    log.info("✅ Device approved: {}", device.getIpAddress());

                    return Uni.createFrom().item(Response.ok(device).build());
                }));
    }

    /**
     * 拒绝设备
     */
    @POST
    @Path("/{id}/reject")
    @RolesAllowed("ADMIN")
    public Uni<Response> rejectDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.<DiscoveredDevice>findById(id)
                .onItem().transformToUni(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(
                                Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Device not found"))
                                        .build());
                    }

                    device.setStatus(DiscoveryStatus.REJECTED);
                    log.info("❌ Device rejected: {}", device.getIpAddress());

                    return Uni.createFrom().item(Response.ok(device).build());
                }));
    }

    /**
     * 删除设备
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Uni<Response> deleteDevice(@PathParam("id") String id) {
        return Panache.withTransaction(() -> DiscoveredDevice.deleteById(id)
                .map(deleted -> {
                    if (deleted) {
                        return Response.noContent().build();
                    }
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(new ErrorResponse("Device not found"))
                            .build();
                }));
    }

    // ============== DTOs ==============

    public record AddDeviceRequest(
            String ipAddress,
            String hostname,
            String macAddress,
            String deviceType,
            String notes,
            String tenantId) {
    }

    public record CountResponse(long count) {
    }

    public record ErrorResponse(String error) {
    }
}
