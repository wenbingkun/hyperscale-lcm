package com.sc.lcm.core.health;

import com.sc.lcm.core.service.LifecycleService;
import com.sc.lcm.core.service.SatelliteStateCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * 就绪检查 (P6-5)
 * 
 * 检查服务是否准备好接收流量
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    @Inject
    LifecycleService lifecycleService;

    @Inject
    SatelliteStateCache stateCache;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("LCM Core Readiness");

        // 检查是否正在关闭
        if (lifecycleService.isShuttingDown()) {
            return builder.down()
                    .withData("reason", "Service is shutting down")
                    .build();
        }

        // 检查 Redis 连接
        boolean redisHealthy = checkRedis();

        if (redisHealthy) {
            return builder.up()
                    .withData("activeRequests", lifecycleService.getActiveRequestCount())
                    .withData("redis", "connected")
                    .build();
        } else {
            return builder.down()
                    .withData("redis", "disconnected")
                    .build();
        }
    }

    private boolean checkRedis() {
        try {
            // 简单的 Redis 健康检查
            stateCache.isOnline("health-check");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
