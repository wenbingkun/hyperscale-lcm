package com.sc.lcm.core.health;

import com.sc.lcm.core.service.LifecycleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * 存活检查 (P6-5)
 * 
 * 检查服务是否活着
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {

    @Inject
    LifecycleService lifecycleService;

    @Override
    public HealthCheckResponse call() {
        // 只要服务进程在运行就返回 UP
        // 即使正在关闭，Liveness 也应该返回 UP（避免被 K8s 重启）
        return HealthCheckResponse.named("LCM Core Liveness")
                .up()
                .withData("activeRequests", lifecycleService.getActiveRequestCount())
                .withData("shuttingDown", lifecycleService.isShuttingDown())
                .build();
    }
}
