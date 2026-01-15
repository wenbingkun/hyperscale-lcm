package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Satellite;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 后台心跳同步服务
 * 定期将 Redis 中的心跳状态批量同步到 PostgreSQL
 * 使用 Hibernate Reactive 实现非阻塞数据库访问
 */
@ApplicationScoped
@Slf4j
public class HeartbeatSyncService {

    @Inject
    SatelliteStateCache stateCache;

    /**
     * 每 30 秒执行一次批量同步
     * 使用响应式方式将 Redis 中的心跳时间更新到 DB
     */
    @Scheduled(every = "30s")
    Uni<Void> syncHeartbeatsToDatabase() {
        log.debug("🔄 Starting heartbeat sync to database...");

        return Panache.withTransaction(() -> Satellite.listAllReactive()
                .onItem().invoke(satellites -> {
                    int syncedCount = 0;

                    for (Satellite sat : satellites) {
                        Long lastHeartbeatMs = stateCache.getLastHeartbeat(sat.getId());
                        if (lastHeartbeatMs != null) {
                            LocalDateTime lastHeartbeat = LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(lastHeartbeatMs),
                                    ZoneId.systemDefault());

                            if (sat.getLastHeartbeat() == null ||
                                    lastHeartbeat.isAfter(sat.getLastHeartbeat())) {
                                sat.setLastHeartbeat(lastHeartbeat);
                                sat.setStatus("ONLINE");
                                syncedCount++;
                            }
                        } else {
                            if ("ONLINE".equals(sat.getStatus())) {
                                sat.setStatus("OFFLINE");
                                syncedCount++;
                            }
                        }
                    }

                    if (syncedCount > 0) {
                        log.info("🔄 Synced {} satellite heartbeats to database", syncedCount);
                    }
                })).replaceWithVoid();
    }
}
