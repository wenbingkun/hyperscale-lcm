package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.LcmService;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.RegisterResponse;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import jakarta.inject.Inject;

// Fault Tolerance imports
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import io.smallrye.faulttolerance.api.RateLimit;
import java.time.temporal.ChronoUnit;

/**
 * gRPC 服务 - 使用 Hibernate Reactive + 限流熔断保护
 */
@GrpcService
@Slf4j
public class LcmGrpcService implements LcmService {

    @Inject
    com.sc.lcm.core.service.SatelliteStateCache stateCache;

    @Inject
    com.sc.lcm.core.service.StreamRegistry streamRegistry;

    /**
     * 响应式 Satellite 注册
     * - 使用 Panache.withTransaction() 非阻塞写入
     * - CircuitBreaker: DB 故障时快速失败
     */
    @Override
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 5000, delayUnit = ChronoUnit.MILLIS)
    public Uni<RegisterResponse> registerSatellite(RegisterRequest request) {
        log.info("Received registration request from host: {}", request.getHostname());

        String id = UUID.randomUUID().toString();
        Satellite satellite = new Satellite(
                id,
                request.getHostname(),
                request.getIpAddress(),
                request.getOsVersion(),
                request.getAgentVersion());
        satellite.setLastHeartbeat(java.time.LocalDateTime.now());

        return Panache.withTransaction(satellite::persist)
                .replaceWith(() -> {
                    log.info("✅ Registered satellite with ID: {}", id);
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
    public Uni<com.sc.lcm.core.grpc.HeartbeatResponse> sendHeartbeat(com.sc.lcm.core.grpc.HeartbeatRequest request) {
        stateCache.updateHeartbeat(request.getSatelliteId());

        log.debug("💓 Heartbeat received for satellite: {}", request.getSatelliteId());

        return Uni.createFrom().item(
                com.sc.lcm.core.grpc.HeartbeatResponse.newBuilder()
                        .setSuccess(true)
                        .build());
    }

    /**
     * 发现事件上报
     */
    @Override
    public Uni<com.sc.lcm.core.grpc.DiscoveryResponse> reportDiscovery(com.sc.lcm.core.grpc.DiscoveryRequest request) {
        log.info("🔍 Discovery Event from Satellite [{}]: Found IP={}, MAC={}, Method={}",
                request.getSatelliteId(),
                request.getDiscoveredIp(),
                request.getMacAddress(),
                request.getDiscoveryMethod());

        // TODO: 响应式持久化发现的资产
        return Uni.createFrom().item(
                com.sc.lcm.core.grpc.DiscoveryResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Discovery processed")
                        .build());
    }

    /**
     * 双向流连接
     */
    @Override
    public io.smallrye.mutiny.Multi<com.sc.lcm.core.grpc.StreamResponse> connectStream(
            io.smallrye.mutiny.Multi<com.sc.lcm.core.grpc.StreamRequest> request) {
        return io.smallrye.mutiny.Multi.createFrom().emitter(emitter -> {
            request.subscribe().with(
                    req -> {
                        if (req.hasInit()) {
                            if (!req.getSatelliteId().isEmpty()) {
                                streamRegistry.register(req.getSatelliteId(), emitter);
                                log.debug("Stream initialized for Satellite: {}", req.getSatelliteId());
                            }
                        } else if (req.hasStatusUpdate()) {
                            var status = req.getStatusUpdate();
                            log.info("📊 JOB STATUS UPDATE: Job={} Status={} Msg={} Exit={}",
                                    status.getJobId(), status.getStatus(), status.getMessage(), status.getExitCode());
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
