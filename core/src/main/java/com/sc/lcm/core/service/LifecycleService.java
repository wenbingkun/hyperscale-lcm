package com.sc.lcm.core.service;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生命周期管理服务 (P6-4)
 * 
 * 负责优雅启动和关闭
 */
@ApplicationScoped
@Slf4j
public class LifecycleService {

    @Inject
    SatelliteStateCache stateCache;

    /** 服务是否正在关闭 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** 当前进行中的请求数 */
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /** 关闭等待超时（秒） */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    void onStart(@Observes StartupEvent ev) {
        log.info("🚀 Hyperscale LCM Core starting up...");
        log.info("✅ Service ready to accept requests");
    }

    void onStop(@Observes ShutdownEvent ev) {
        log.info("🛑 Hyperscale LCM Core shutting down...");
        shuttingDown.set(true);

        // 等待进行中的请求完成
        int waitedSeconds = 0;
        while (activeRequests.get() > 0 && waitedSeconds < SHUTDOWN_TIMEOUT_SECONDS) {
            log.info("⏳ Waiting for {} active requests to complete...", activeRequests.get());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waitedSeconds++;
        }

        if (activeRequests.get() > 0) {
            log.warn("⚠️ Shutdown timeout reached with {} active requests", activeRequests.get());
        }

        log.info("👋 Hyperscale LCM Core shutdown complete");
    }

    /**
     * 检查服务是否正在关闭
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * 开始处理请求（用于跟踪）
     */
    public void beginRequest() {
        activeRequests.incrementAndGet();
    }

    /**
     * 结束处理请求
     */
    public void endRequest() {
        activeRequests.decrementAndGet();
    }

    /**
     * 获取当前活跃请求数
     */
    public int getActiveRequestCount() {
        return activeRequests.get();
    }
}
