package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryMethod;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.DiscoveryRequest;
import com.sc.lcm.core.grpc.DiscoveryResponse;
import com.sc.lcm.core.grpc.HeartbeatRequest;
import com.sc.lcm.core.grpc.HeartbeatResponse;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.RegisterResponse;
import com.sc.lcm.core.grpc.StreamRequest;
import com.sc.lcm.core.grpc.StreamResponse;
import com.sc.lcm.core.service.JobStatusForwarder;
import com.sc.lcm.core.service.RegistrationNodeSpecsProvider;
import com.sc.lcm.core.service.SatelliteStateCache;
import com.sc.lcm.core.service.StreamRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.faulttolerance.api.RateLimit;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
    RegistrationNodeSpecsProvider registrationNodeSpecs;

    @ConfigProperty(name = "lcm.discovery.require-approval", defaultValue = "false")
    boolean requireApproval;

    @Inject
    JobStatusForwarder jobStatusForwarder;

    /**
     * 响应式 Satellite 注册
     * - CircuitBreaker: DB 故障时快速失败
     * - 校验发现设备池的状态 (MANAGED/APPROVED)
     * - 同时持久化 Node 实体（如果包含硬件规格）
     */
    @Override
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 5000, delayUnit = ChronoUnit.MILLIS)
    public Uni<RegisterResponse> registerSatellite(RegisterRequest request) {
        log.info("Received registration request from host: {}", request.getHostname());

        return Panache.withTransaction(() -> {
            if (requireApproval) {
                return DiscoveredDevice.findByIp(request.getIpAddress())
                        .flatMap(device -> {
                            if (device == null) {
                                log.warn("Registration rejected for {}: device unknown", request.getIpAddress());
                                return Uni.createFrom()
                                        .failure(new StatusRuntimeException(Status.UNAUTHENTICATED
                                                .withDescription("Device not discovered or approved")));
                            }
                            if (device.getStatus() != DiscoveryStatus.APPROVED
                                    && device.getStatus() != DiscoveryStatus.MANAGED) {
                                log.warn("Registration rejected for {}: device status is {}", request.getIpAddress(),
                                        device.getStatus());
                                return Uni.createFrom()
                                        .failure(new StatusRuntimeException(
                                                Status.UNAUTHENTICATED.withDescription(
                                                        "Device not approved for registration. Current status: "
                                                                + device.getStatus())));
                            }
                            // Device is approved, mark as MANAGED
                            device.setStatus(DiscoveryStatus.MANAGED);
                            return doRegisterSatellite(request);
                        });
            } else {
                return doRegisterSatellite(request);
            }
        });
    }

    private Uni<RegisterResponse> doRegisterSatellite(RegisterRequest request) {
        String id = UUID.randomUUID().toString();
        Satellite satellite = new Satellite(
                id,
                request.getClusterId(),
                request.getHostname(),
                request.getIpAddress(),
                request.getOsVersion(),
                request.getAgentVersion());
        satellite.setLastHeartbeat(LocalDateTime.now());

        // 缓存硬件规格（用于调度时快速查询）
        if (request.hasHardware()) {
            registrationNodeSpecs.cacheHardwareSpecs(id, request.getHardware());
        }

        return satellite.<Satellite>persist()
                .replaceWithVoid()
                .flatMap(v -> {
                    // 如果有硬件规格，同步持久化 Node 实体
                    if (request.hasHardware()) {
                        return registrationNodeSpecs.persistNode(id, request.getHardware());
                    }
                    return Uni.createFrom().voidItem();
                })
                .map(v -> {
                    log.info("Registered satellite with ID: {} (Node synced: {})", id, request.hasHardware());
                    return RegisterResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("Registration Successful")
                            .setAssignedId(id)
                            .build();
                });
    }

    /**
     * 心跳处理 - 高性能路径
     * - RateLimit: 限制 2000 req/s 防止流量冲击
     * - Bulkhead: 最多 200 并发防止资源耗尽
     */
    @Override
    @RateLimit(value = 2000, window = 1, windowUnit = ChronoUnit.SECONDS)
    @Bulkhead(value = 200)
    public Uni<HeartbeatResponse> sendHeartbeat(HeartbeatRequest request) {
        log.debug("Heartbeat received for satellite: {}", request.getSatelliteId());

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

        // TODO(multi-cluster): request.getClusterId() is received but not yet used.
        // Future work: validate that the satellite's cluster affinity matches what is
        // stored at registration time, and route scheduling partitions accordingly.
        return stateCache.updateHeartbeatReactive(request.getSatelliteId())
                .replaceWith(() -> HeartbeatResponse.newBuilder()
                        .setSuccess(true)
                        .build());
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
                        log.debug("Updated existing device: {}", request.getDiscoveredIp());
                        return Uni.createFrom().item(existing);
                    } else {
                        DiscoveredDevice device = new DiscoveredDevice();
                        device.setIpAddress(request.getDiscoveredIp());
                        device.setMacAddress(request.getMacAddress());
                        device.setDiscoveryMethod(mapDiscoveryMethod(request.getDiscoveryMethod()));
                        device.setDiscoveredAt(LocalDateTime.now());
                        log.info("New device discovered and persisted: {}", request.getDiscoveredIp());
                        return device.persist();
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

                            jobStatusForwarder.forwardStatus(
                                    status.getJobId(),
                                    req.getSatelliteId(),
                                    status.getStatus().name(),
                                    status.getExitCode(),
                                    status.getMessage(),
                                    status.getTraceContextMap());
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
