package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryMethod;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.DiscoveryRequest;
import com.sc.lcm.core.grpc.DiscoveryResponse;
import com.sc.lcm.core.grpc.HeartbeatRequest;
import com.sc.lcm.core.grpc.HeartbeatResponse;
import com.sc.lcm.core.domain.JobStatusCallback;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.RegisterResponse;
import com.sc.lcm.core.grpc.StreamRequest;
import com.sc.lcm.core.grpc.StreamResponse;
import com.sc.lcm.core.service.JobStatusForwarder;
import com.sc.lcm.core.service.JobExecutionService;
import com.sc.lcm.core.service.DeviceClaimPlanner;
import com.sc.lcm.core.service.SatelliteRegistrationService;
import com.sc.lcm.core.service.SatelliteStateCache;
import com.sc.lcm.core.service.StreamRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.faulttolerance.api.RateLimit;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * gRPC 服务 - 使用 Hibernate Reactive + 限流熔断保护
 */
@GrpcService
@Slf4j
public class LcmGrpcService implements LcmService {

    @Inject
    DashboardWebSocket dashboardWebSocket;

    @Inject
    SatelliteStateCache stateCache;

    @Inject
    StreamRegistry streamRegistry;

    @Inject
    SatelliteRegistrationService satelliteRegistrationService;

    @ConfigProperty(name = "lcm.discovery.require-approval", defaultValue = "false")
    boolean requireApproval;

    @Inject
    JobStatusForwarder jobStatusForwarder;

    @Inject
    JobExecutionService jobExecutionService;

    @Inject
    DeviceClaimPlanner deviceClaimPlanner;

    /**
     * 响应式 Satellite 注册
     * - CircuitBreaker: DB 故障时快速失败
     * - 校验发现设备池的状态 (MANAGED/APPROVED)
     * - 同时持久化 Node 实体（如果包含硬件规格）
     */
    @Override
    public Uni<RegisterResponse> registerSatellite(RegisterRequest request) {
        log.info("Received registration request from host: {}", request.getHostname());
        return satelliteRegistrationService.register(request, requireApproval);
    }

    /**
     * 心跳处理 - 高性能路径
     * - RateLimit: 限制 2000 req/s 防止流量冲击
     * - Bulkhead: 最多 200 并发防止资源耗尽
     */
    @Override
    @RateLimit(value = 2000, window = 1, windowUnit = ChronoUnit.SECONDS)
    @Bulkhead(value = 200)
    @WithSession
    public Uni<HeartbeatResponse> sendHeartbeat(HeartbeatRequest request) {
        log.debug("Heartbeat received for satellite: {}", request.getSatelliteId());

        String requestedClusterId = normalizeClusterId(request.getClusterId());
        return Satellite.findByIdReactive(request.getSatelliteId())
                .flatMap(satellite -> {
                    if (satellite == null) {
                        log.warn("Heartbeat rejected for unknown satellite: {}", request.getSatelliteId());
                        return Uni.createFrom().failure(new StatusRuntimeException(
                                Status.NOT_FOUND.withDescription("Satellite not found: " + request.getSatelliteId())));
                    }

                    validateHeartbeatCluster(satellite, requestedClusterId, request.getSatelliteId());

                    double avgGpu = 0.0;
                    if (request.getGpuCount() > 0 && request.getGpuMetricsCount() > 0) {
                        avgGpu = request.getGpuMetricsList().stream()
                                .mapToDouble(m -> m.getUtilizationPercent())
                                .average().orElse(0.0);
                    }

                    dashboardWebSocket.broadcastHeartbeat(WsEvent.HeartbeatPayload.builder()
                            .nodeId(request.getSatelliteId())
                            .cpuPercent(request.getCpuUsagePercent())
                            .loadAvg(request.getLoadAvg())
                            .memoryUsedMb(request.getMemoryUsedBytes() / 1024 / 1024)
                            .memoryTotalMb(request.getMemoryTotalBytes() / 1024 / 1024)
                            .gpuCount(request.getGpuCount())
                            .gpuAvgUtil(avgGpu)
                            .powerState(request.getPowerState())
                            .systemTemperatureCelsius(request.getSystemTemperatureCelsius())
                            .build());

                    return stateCache.updateHeartbeatReactive(request.getSatelliteId())
                            .replaceWith(HeartbeatResponse.newBuilder()
                                    .setSuccess(true)
                                    .build());
                });
    }

    private String normalizeClusterId(String clusterId) {
        return (clusterId == null || clusterId.isBlank()) ? "default" : clusterId;
    }

    static void validateHeartbeatCluster(Satellite satellite, String requestedClusterId, String satelliteId) {
        String registeredClusterId = normalizeClusterIdStatic(satellite.getClusterId());
        String effectiveRequestedClusterId = normalizeClusterIdStatic(requestedClusterId);
        if (!registeredClusterId.equals(effectiveRequestedClusterId)) {
            throw new StatusRuntimeException(
                    Status.FAILED_PRECONDITION.withDescription(
                            "Heartbeat cluster mismatch for satellite " + satelliteId));
        }
    }

    private static String normalizeClusterIdStatic(String clusterId) {
        return (clusterId == null || clusterId.isBlank()) ? "default" : clusterId;
    }

    /**
     * 发现事件上报 - 响应式持久化到 discovered_devices 表
     */
    @Override
    public Uni<DiscoveryResponse> reportDiscovery(DiscoveryRequest request) {
        log.info("Discovery event from Satellite [{}]: IP={}, MAC={}, Method={}",
                request.getSatelliteId(),
                request.getDiscoveredIp(),
                request.getMacAddress(),
                request.getDiscoveryMethod());

        dashboardWebSocket.broadcastDiscovery(request.getDiscoveredIp(), request.getMacAddress(),
                request.getDiscoveryMethod());

        return Panache.withTransaction(() -> DiscoveredDevice.findByIp(request.getDiscoveredIp())
                .onItem().transformToUni(existing -> {
                    if (existing != null) {
                        existing.setLastProbedAt(LocalDateTime.now());
                        existing.setMacAddress(request.getMacAddress());
                        applyDiscoveryHints(existing, request);
                        log.debug("Updated existing device: {}", request.getDiscoveredIp());
                        return deviceClaimPlanner.plan(existing);
                    } else {
                        DiscoveredDevice device = new DiscoveredDevice();
                        device.setIpAddress(request.getDiscoveredIp());
                        device.setMacAddress(request.getMacAddress());
                        device.setDiscoveryMethod(mapDiscoveryMethod(request.getDiscoveryMethod()));
                        device.setDiscoveredAt(LocalDateTime.now());
                        applyDiscoveryHints(device, request);
                        log.info("New device discovered and persisted: {}", request.getDiscoveredIp());
                        return deviceClaimPlanner.plan(device)
                                .onItem().transformToUni(planned -> planned.persist().replaceWith(planned));
                    }
                })).replaceWith(
                        DiscoveryResponse.newBuilder()
                                .setSuccess(true)
                                .setMessage("Discovery persisted")
                                .build())
                .onFailure().recoverWithItem(error -> {
                    log.error("Failed to persist discovery: {}", error.getMessage());
                    return DiscoveryResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Persistence failed: " + error.getMessage())
                            .build();
                });
    }

    static boolean isBmcDiscoveryCandidate(DiscoveryRequest request) {
        if (request == null) {
            return false;
        }

        String discoveryMethod = request.getDiscoveryMethod();
        if (discoveryMethod != null && !discoveryMethod.isBlank()) {
            String upper = discoveryMethod.trim().toUpperCase();
            if (upper.contains("REDFISH") || upper.contains("BMC") || upper.contains("IPMI")) {
                return true;
            }
        }

        String discoveredIp = request.getDiscoveredIp();
        return discoveredIp != null
                && (discoveredIp.startsWith("https://") || discoveredIp.startsWith("http://"));
    }

    static void applyDiscoveryHints(DiscoveredDevice device, DiscoveryRequest request) {
        if (device == null || !isBmcDiscoveryCandidate(request)) {
            return;
        }

        device.setInferredType("BMC_ENABLED");
        if (device.getBmcAddress() == null || device.getBmcAddress().isBlank()) {
            device.setBmcAddress(request.getDiscoveredIp());
        }
    }

    /**
     * 将 gRPC 发现方法字符串映射为枚举。
     * 支持 DHCP_DISCOVER, DHCP_OFFER, DHCP_REQUEST, DHCP_ACK -> DHCP
     * 支持 ARP_SCAN, PING_SCAN -> SCAN
     */
    private DiscoveryMethod mapDiscoveryMethod(String method) {
        if (method == null || method.isEmpty()) {
            return DiscoveryMethod.SCAN;
        }
        String upper = method.toUpperCase();
        if (upper.startsWith("DHCP")) {
            return DiscoveryMethod.DHCP;
        }
        if (upper.contains("SCAN") || upper.contains("PING") || upper.contains("ARP")) {
            return DiscoveryMethod.SCAN;
        }
        try {
            return DiscoveryMethod.valueOf(upper);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown discovery method '{}', defaulting to SCAN", method);
            return DiscoveryMethod.SCAN;
        }
    }

    /**
     * 双向流连接
     */
    @Override
    public Multi<StreamResponse> connectStream(Multi<StreamRequest> request) {
        return Multi.createFrom().emitter(emitter -> {
            request.subscribe().with(
                    req -> {
                        if (req.hasInit()) {
                            if (!req.getSatelliteId().isEmpty()) {
                                streamRegistry.register(req.getSatelliteId(), emitter);
                                log.debug("Stream initialized for Satellite: {}", req.getSatelliteId());
                            }
                        } else if (req.hasStatusUpdate()) {
                            var status = req.getStatusUpdate();
                            log.info("JOB STATUS UPDATE: Job={} Status={} Msg={} Exit={}",
                                    status.getJobId(), status.getStatus(), status.getMessage(), status.getExitCode());

                            JobStatusCallback callback = jobStatusForwarder.forwardStatus(
                                    status.getJobId(),
                                    req.getSatelliteId(),
                                    status.getStatus().name(),
                                    status.getExitCode(),
                                    status.getMessage(),
                                    status.getTraceContextMap());
                            jobExecutionService.processJobStatusCallback(callback)
                                    .subscribe().with(
                                            ignored -> {
                                            },
                                            failure -> log.error("Failed to process direct job status callback", failure));
                        } else {
                            if (!req.getSatelliteId().isEmpty()) {
                                streamRegistry.register(req.getSatelliteId(), emitter);
                            }
                        }
                    },
                    failure -> {
                        log.error("Stream error", failure);
                        emitter.fail(failure);
                    },
                    () -> {
                        log.info("Stream completed");
                        emitter.complete();
                    });
        });
    }
}
