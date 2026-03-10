package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Satellite;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Path("/api/clusters")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR", "USER" })
public class ClusterResource {

    @GET
    public Uni<List<ClusterSummary>> listClusters() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(2);
        return Satellite.listAllReactive()
                .map(satellites -> satellites.stream()
                        .filter(satellite -> satellite.getClusterId() != null && !satellite.getClusterId().isBlank())
                        .collect(java.util.stream.Collectors.groupingBy(Satellite::getClusterId))
                        .entrySet().stream()
                        .map(entry -> toSummary(entry.getKey(), entry.getValue(), since))
                        .sorted(Comparator.comparing(ClusterSummary::clusterId))
                        .toList());
    }

    @GET
    @Path("/{clusterId}")
    public Uni<Response> getCluster(@PathParam("clusterId") String clusterId) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(2);
        return Satellite.findByCluster(clusterId)
                .map(satellites -> {
                    if (satellites.isEmpty()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Cluster not found: " + clusterId))
                                .build();
                    }
                    return Response.ok(toSummary(clusterId, satellites, since)).build();
                });
    }

    @GET
    @Path("/{clusterId}/nodes")
    public Uni<List<Satellite>> listClusterNodes(
            @PathParam("clusterId") String clusterId,
            @QueryParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(2);
        if ("online".equalsIgnoreCase(status)) {
            return Satellite.findActiveByCluster(since, clusterId);
        }
        return Satellite.find("clusterId", clusterId).page(0, limit).list();
    }

    private ClusterSummary toSummary(String clusterId, List<Satellite> satellites, LocalDateTime since) {
        long onlineNodes = satellites.stream()
                .filter(satellite -> satellite.getLastHeartbeat() != null && satellite.getLastHeartbeat().isAfter(since))
                .count();

        LocalDateTime lastHeartbeat = satellites.stream()
                .map(Satellite::getLastHeartbeat)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return new ClusterSummary(clusterId, satellites.size(), onlineNodes, lastHeartbeat);
    }

    public record ClusterSummary(
            String clusterId,
            long totalNodes,
            long onlineNodes,
            LocalDateTime lastHeartbeatAt) {
    }

    public record ErrorResponse(String error) {
    }
}
