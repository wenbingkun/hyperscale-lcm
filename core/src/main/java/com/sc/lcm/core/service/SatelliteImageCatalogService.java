package com.sc.lcm.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.Satellite;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SatelliteImageCatalogService {

    private static final TypeReference<List<ImageDescriptor>> IMAGE_LIST_TYPE = new TypeReference<>() {
    };

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.satellite.image-api.scheme", defaultValue = "http")
    String imageApiScheme;

    @ConfigProperty(name = "lcm.satellite.image-api.port", defaultValue = "8090")
    int imageApiPort;

    @ConfigProperty(name = "lcm.satellite.image-api.path", defaultValue = "/api/images")
    String imageApiPath;

    @ConfigProperty(name = "lcm.satellite.image-api.connect-timeout-ms", defaultValue = "1000")
    int connectTimeoutMs;

    @ConfigProperty(name = "lcm.satellite.image-api.request-timeout-ms", defaultValue = "3000")
    int requestTimeoutMs;

    private HttpClient imageApiHttpClient;

    @PostConstruct
    void init() {
        imageApiHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    public Uni<List<SatelliteImageCatalogEntry>> listImages(String clusterId, boolean onlineOnly) {
        return loadSatellites(clusterId, onlineOnly)
                .chain(satellites -> {
                    if (satellites.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    return Multi.createFrom().iterable(satellites)
                            .onItem().transformToUniAndConcatenate(this::fetchImagesForSatellite)
                            .collect().asList();
                });
    }

    @WithSession
    Uni<List<Satellite>> loadSatellites(String clusterId, boolean onlineOnly) {
        Uni<List<Satellite>> satellitesUni = clusterId == null || clusterId.isBlank()
                ? Satellite.listAllReactive()
                : Satellite.findByCluster(clusterId);

        return satellitesUni.map(satellites -> satellites.stream()
                .filter(satellite -> !onlineOnly || isOnline(satellite))
                .sorted(java.util.Comparator.comparing(Satellite::getId))
                .toList());
    }

    private boolean isOnline(Satellite satellite) {
        return satellite.getLastHeartbeat() != null
                && satellite.getLastHeartbeat().isAfter(LocalDateTime.now().minusMinutes(2));
    }

    private Uni<SatelliteImageCatalogEntry> fetchImagesForSatellite(Satellite satellite) {
        URI endpoint = endpointFor(satellite);
        if (endpoint == null) {
            return Uni.createFrom().item(unavailable(satellite, null, "Satellite address is missing"));
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .GET()
                .build();

        return Uni.createFrom().completionStage(
                imageApiHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .onItem().transform(response -> toCatalogEntry(satellite, endpoint, response))
                .onFailure().recoverWithItem(error -> unavailable(
                        satellite,
                        endpoint.toString(),
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }

    private SatelliteImageCatalogEntry toCatalogEntry(
            Satellite satellite,
            URI endpoint,
            HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return unavailable(satellite, endpoint.toString(),
                    "Satellite image API returned HTTP " + response.statusCode());
        }

        try {
            List<ImageDescriptor> images = objectMapper.readValue(response.body(), IMAGE_LIST_TYPE);
            return new SatelliteImageCatalogEntry(
                    satellite.getId(),
                    satellite.getClusterId(),
                    satellite.getHostname(),
                    satellite.getIpAddress(),
                    isOnline(satellite),
                    endpoint.toString(),
                    true,
                    images,
                    null);
        } catch (Exception error) {
            return unavailable(satellite, endpoint.toString(),
                    "Failed to parse satellite image catalog: " + error.getMessage());
        }
    }

    private SatelliteImageCatalogEntry unavailable(Satellite satellite, String endpoint, String error) {
        return new SatelliteImageCatalogEntry(
                satellite.getId(),
                satellite.getClusterId(),
                satellite.getHostname(),
                satellite.getIpAddress(),
                isOnline(satellite),
                endpoint,
                false,
                List.of(),
                error);
    }

    private URI endpointFor(Satellite satellite) {
        String host = firstNonBlank(satellite.getIpAddress(), satellite.getHostname());
        if (host == null) {
            return null;
        }

        String normalizedPath = imageApiPath.startsWith("/") ? imageApiPath : "/" + imageApiPath;
        return URI.create(String.format("%s://%s:%d%s", imageApiScheme, host, imageApiPort, normalizedPath));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    public record SatelliteImageCatalogEntry(
            String satelliteId,
            String clusterId,
            String hostname,
            String ipAddress,
            boolean online,
            String endpoint,
            boolean reachable,
            List<ImageDescriptor> images,
            String error) {
    }

    public record ImageDescriptor(
            String name,
            long sizeBytes,
            String contentType,
            long lastModifiedEpochMs) {
    }
}
