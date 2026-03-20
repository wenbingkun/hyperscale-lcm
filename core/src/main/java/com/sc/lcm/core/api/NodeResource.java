package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Node;
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
import java.util.Map;
import java.util.stream.Collectors;

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
         * 列出所有节点（包含 Redfish/BMC 硬件规格）
         */
        @GET
        public Uni<List<NodeResponse>> listNodes(
                        @QueryParam("status") String status,
                        @QueryParam("limit") @DefaultValue("100") int limit) {

                Uni<List<Satellite>> satellitesUni = "online".equalsIgnoreCase(status)
                        ? Satellite.findActive(LocalDateTime.now().minusMinutes(2))
                        : Satellite.findAll().page(0, limit).list();

                return satellitesUni.onItem().transformToUni(satellites -> {
                        List<String> ids = satellites.stream().map(Satellite::getId).toList();
                        if (ids.isEmpty()) {
                                return Uni.createFrom().item(List.<NodeResponse>of());
                        }
                        return Node.<Node>list("id IN (?1)", ids)
                                .map(nodes -> {
                                        Map<String, Node> nodeById = nodes.stream()
                                                .collect(Collectors.toMap(Node::getId, n -> n));
                                        return satellites.stream()
                                                .map(s -> NodeResponse.of(s, nodeById.get(s.getId()),
                                                        stateCache.isOnline(s.getId()),
                                                        stateCache.getLastHeartbeat(s.getId())))
                                                .toList();
                                });
                });
        }

        /**
         * 获取单个节点详情（包含 Redfish/BMC 硬件规格）
         */
        @GET
        @Path("/{id}")
        public Uni<Response> getNode(@PathParam("id") String id) {
                return Uni.combine().all()
                                .unis(Satellite.<Satellite>findById(id), Node.<Node>findById(id))
                                .asTuple()
                                .map(tuple -> {
                                        Satellite satellite = tuple.getItem1();
                                        if (satellite == null) {
                                                return Response.status(Response.Status.NOT_FOUND)
                                                                .entity(new ErrorResponse("Node not found: " + id))
                                                                .build();
                                        }
                                        return Response.ok(NodeResponse.of(
                                                        satellite,
                                                        tuple.getItem2(),
                                                        stateCache.isOnline(id),
                                                        stateCache.getLastHeartbeat(id))).build();
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
                                                                        new NodeStatusResponse(id, request.status(),
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

                Uni<List<Node>> nodesUni = Node.<Node>listAll();

                return Uni.combine().all().unis(activeUni, totalUni, nodesUni)
                                .asTuple()
                                .onItem().transform(tuple -> {
                                        long onlineCount = tuple.getItem1().size();
                                        long totalNodes = tuple.getItem2();
                                        List<Node> nodes = tuple.getItem3();

                                        long totalCpuCores = nodes.stream().mapToLong(Node::getCpuCores).sum();
                                        long totalGpus = nodes.stream().mapToLong(Node::getGpuCount).sum();
                                        long totalMemoryGb = nodes.stream().mapToLong(Node::getMemoryGb).sum();

                                        return new ClusterStats(onlineCount, totalNodes, totalCpuCores, totalGpus, totalMemoryGb);
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

        public record NodeResponse(
                        String id,
                        String hostname,
                        String ipAddress,
                        String osVersion,
                        String agentVersion,
                        String status,
                        boolean online,
                        Long lastHeartbeatMs,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt,
                        // Hardware specs from Node entity
                        int cpuCores,
                        int gpuCount,
                        String gpuModel,
                        long memoryGb,
                        String rackId,
                        String zoneId,
                        // Redfish / BMC info
                        String bmcIp,
                        String bmcMac,
                        String systemSerial,
                        String systemModel) {

                static NodeResponse of(Satellite s, Node n, boolean online, Long lastHeartbeatMs) {
                        return new NodeResponse(
                                        s.getId(),
                                        s.getHostname(),
                                        s.getIpAddress(),
                                        s.getOsVersion(),
                                        s.getAgentVersion(),
                                        s.getStatus(),
                                        online,
                                        lastHeartbeatMs,
                                        s.getCreatedAt(),
                                        s.getUpdatedAt(),
                                        n != null ? n.getCpuCores() : 0,
                                        n != null ? n.getGpuCount() : 0,
                                        n != null ? n.getGpuModel() : null,
                                        n != null ? n.getMemoryGb() : 0L,
                                        n != null ? n.getRackId() : null,
                                        n != null ? n.getZoneId() : null,
                                        n != null ? n.getBmcIp() : null,
                                        n != null ? n.getBmcMac() : null,
                                        n != null ? n.getSystemSerial() : null,
                                        n != null ? n.getSystemModel() : null);
                }
        }

        public record NodeStatusRequest(String status) {
        }

        public record NodeStatusResponse(String id, String status, String message) {
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
