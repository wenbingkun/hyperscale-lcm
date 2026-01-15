package com.sc.lcm.core.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 缓存服务测试
 * 测试 Satellite 心跳状态缓存功能
 */
@QuarkusTest
public class SatelliteStateCacheTest {

    @Inject
    SatelliteStateCache stateCache;

    @Test
    @DisplayName("测试心跳更新后状态变为在线")
    void testUpdateAndCheckHeartbeat() {
        String satelliteId = "test-satellite-001";

        // 初始状态应为离线（没有心跳记录）
        assertFalse(stateCache.isOnline(satelliteId),
                "新 Satellite 初始应为离线状态");

        // 更新心跳后应为在线
        stateCache.updateHeartbeat(satelliteId);
        assertTrue(stateCache.isOnline(satelliteId),
                "心跳更新后 Satellite 应为在线状态");

        // 验证时间戳存在且正确
        Long lastHeartbeat = stateCache.getLastHeartbeat(satelliteId);
        assertNotNull(lastHeartbeat, "心跳时间戳不应为空");
        assertTrue(System.currentTimeMillis() - lastHeartbeat < 1000,
                "心跳时间戳应在最近1秒内");
    }

    @Test
    @DisplayName("测试多个 Satellite 心跳独立性")
    void testMultipleSatelliteHeartbeats() {
        String satellite1 = "test-satellite-multi-001";
        String satellite2 = "test-satellite-multi-002";

        // 只更新第一个
        stateCache.updateHeartbeat(satellite1);

        assertTrue(stateCache.isOnline(satellite1),
                "Satellite 1 应在线");
        assertFalse(stateCache.isOnline(satellite2),
                "Satellite 2 应离线（未发送心跳）");

        // 更新第二个
        stateCache.updateHeartbeat(satellite2);

        assertTrue(stateCache.isOnline(satellite1),
                "Satellite 1 仍应在线");
        assertTrue(stateCache.isOnline(satellite2),
                "Satellite 2 现在应在线");
    }

    @Test
    @DisplayName("测试获取不存在的心跳返回 null")
    void testGetNonExistentHeartbeat() {
        String nonExistentId = "non-existent-satellite-" + System.currentTimeMillis();

        Long heartbeat = stateCache.getLastHeartbeat(nonExistentId);
        assertNull(heartbeat, "不存在的 Satellite 心跳应返回 null");
    }
}
