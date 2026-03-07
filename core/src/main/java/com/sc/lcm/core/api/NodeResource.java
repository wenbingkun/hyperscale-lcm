package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.service.SatelliteStateCache;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点管理 REST API (P4-2)
 */
@Path("/api/nodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j
public class NodeResource {

        @Inject
        SatelliteStateCache stateCache;

        /**
         * 列出所有节点
         */
        @GET
        public Uni<List<Satellite>> listNodes(
                        @QueryParam("status") String status,
                        @QueryParam("limit") @DefaultValue("100") int limit) {

                if ("online".equalsIgnoreCase(status)) {
                        return Satellite.findActive(LocalDateTime.now().minusMinutes(2));
                }
                return Satellite.findAll().page(0, limit).list();
        }

        /**
         * 获取单个节点详情
         */
        @GET
        @Path("/{id}")
        public Uni<Response> getNode(@PathParam("id") String id) {
                return Satellite.findByIdReactive(id)
                                .onItem().transform(satellite -> {
                                        if (satellite == null) {
                                                return Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Node not found: " + id))
                                                                .build();
                                        }

                                        // 附加在线状态
                                        boolean isOnline = stateCache.isOnline(id);
                                        Long lastHeartbeat = stateCache.getLastHeartbeat(id);

                                        return Response.ok(new NodeDetailResponse(
                                                        satellite.getId(),
                                                        satellite.getHostname(),
                                                        satellite.getIpAddress(),
                                                        satellite.getOsVersion(),
                                                        satellite.getAgentVersion(),
                                                        satellite.getStatus(),
                                                        isOnline,
                                                        lastHeartbeat,
                                                        satellite.getCreatedAt(),
                                                        satellite.getUpdatedAt())).build();
                                });
        }

        /**
         * 更新节点状态（维护模式）
         */
        @PUT
        @Path("/{id}/status")
        public Uni<Response> updateNodeStatus(
                        @PathParam("id") String id,
                        NodeStatusRequest request) {

                return io.quarkus.hibernate.reactive.panache.Panache
                                .withTransaction(() -> Satellite.findByIdReactive(id)
                                                .onItem().transformToUni(satellite -> {
                                                        if (satellite == null) {
                                                                return Uni.createFrom().item(Response
                                                                                .status(Response.Status.NOT_FOUND)
                                                                                .entity(new ErrorResponse(
                                                                                                "Node not found: "
                                                                                                                + id))
                                                                                .build());
                                                        }

                                                        satellite.setStatus(request.status());
                                                        log.info("🔧 Node {} status updated to: {}", id,
                                                                        request.status());

                                                        return Uni.createFrom().item(Response.ok(
                                                                        new NodeResponse(id, request.status(),
                                                                                        "Status updated"))
                                                                        .build());
                                                }));
        }

        /**
         * 获取集群统计
         */
        @GET
        @Path("/stats")
        public Uni<ClusterStats> getClusterStats() {
                Uni<List<Satellite>> activeUni = Satellite.findActive(LocalDateTime.now().minusMinutes(2));
                Uni<Long> totalUni = Satellite.count();

                return Uni.combine().all().unis(activeUni, totalUni)
                                .asTuple()
                                .onItem().transform(tuple -> {
                                        long onlineCount = tuple.getItem1().size();
                                        long totalNodes = tuple.getItem2();

                                        return new ClusterStats(
                                                        onlineCount,
                                                        totalNodes,
                                                        0L, // CPU cores: not tracked in Satellite entity yet
                                                        0L, // GPUs: not tracked in Satellite entity yet
                                                        0L // Memory (GB): not tracked in Satellite entity yet
                                        );
                                });
        }

        /**
         * 获取在线节点数量
         */
        @GET
        @Path("/online/count")
        public Uni<OnlineCountResponse> getOnlineCount() {
                return Satellite.findActive(LocalDateTime.now().minusMinutes(2))
                                .onItem().transform(satellites -> new OnlineCountResponse(satellites.size()));
        }

        // ============== DTO Records ==============

        public record NodeDetailResponse(
                        String id,
                        String hostname,
                        String ipAddress,
                        String osVersion,
                        String agentVersion,
                        String status,
                        boolean online,
                        Long lastHeartbeatMs,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt) {
        }

        public record NodeStatusRequest(String status) {
        }

        public record NodeResponse(String id, String status, String message) {
        }

        public record ClusterStats(
                        long onlineNodes,
                        long totalNodes,
                        long totalCpuCores,
                        long totalGpus,
                        long totalMemoryGb) {
        }

        public record OnlineCountResponse(long count) {
        }

        public record ErrorResponse(String error) {
        }
}
