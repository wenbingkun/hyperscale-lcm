package com.sc.lcm.core.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.redis.datasource.value.SetArgs;

@ApplicationScoped
public class SatelliteStateCache {

    private final ValueCommands<String, Long> heartbeatCommands;

    // We can inject RedisDataSource directly
    public SatelliteStateCache(RedisDataSource ds) {
        this.heartbeatCommands = ds.value(Long.class);
    }

    public void updateHeartbeat(String satelliteId) {
        // Set heartbeat timestamp (TTL 5 minutes)
        heartbeatCommands.set("satellite:" + satelliteId + ":heartbeat", System.currentTimeMillis(),
                new SetArgs().ex(300));
    }

    public boolean isOnline(String satelliteId) {
        Long lastHeartbeat = heartbeatCommands.get("satellite:" + satelliteId + ":heartbeat");
        if (lastHeartbeat == null) {
            return false;
        }
        // Online if heartbeat within last 2 minutes (120000 ms)
        return (System.currentTimeMillis() - lastHeartbeat) < 120000;
    }

    public Long getLastHeartbeat(String satelliteId) {
        return heartbeatCommands.get("satellite:" + satelliteId + ":heartbeat");
    }

    /**
     * 获取在线节点数量
     * 注意：生产环境中应使用 SCAN 而非 KEYS
     */
    public int getOnlineCount() {
        // 简化实现：遍历已知节点检查状态
        // 生产环境应使用 Redis SET 存储在线节点列表
        return 0; // TODO: 实现精确计数
    }
}
