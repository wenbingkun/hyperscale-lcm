package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.HardwareSpecs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegistrationNodeSpecsProvider 单元测试
 * 
 * 验证硬件规格缓存和 Node 创建逻辑的正确性。
 * 不需要 Quarkus 环境和数据库，纯单元测试。
 */
class RegistrationNodeSpecsProviderTest {

    private RegistrationNodeSpecsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RegistrationNodeSpecsProvider();
    }

    @Test
    @DisplayName("缓存硬件规格后应返回正确的 Node")
    void testGetNodeSpecsWithCachedHardware() {
        // 构建硬件规格
        HardwareSpecs specs = HardwareSpecs.newBuilder()
                .setCpuCores(64)
                .setMemoryGb(512)
                .setGpuCount(8)
                .setGpuModel("A100")
                .setGpuTopology("NVLink")
                .setNvlinkBandwidthGbps(600)
                .setRackId("rack-01")
                .setZoneId("zone-a")
                .build();

        // 缓存
        provider.cacheHardwareSpecs("sat-001", specs);

        // 创建 Satellite
        Satellite satellite = new Satellite("sat-001", "default", "gpu-node-01", "10.0.0.1", "Linux 6.5", "0.3.0");

        // 验证 Node 创建
        Node node = provider.getNodeSpecs(satellite);

        assertEquals("sat-001", node.getId());
        assertEquals(64, node.getCpuCores());
        assertEquals(512, node.getMemoryGb());
        assertEquals(8, node.getGpuCount());
        assertEquals("A100", node.getGpuModel());
        assertEquals("NVLink", node.getGpuTopology());
        assertEquals(600, node.getNvlinkBandwidthGbps());
        assertEquals("rack-01", node.getRackId());
        assertEquals("zone-a", node.getZoneId());
    }

    @Test
    @DisplayName("无缓存时应返回默认 Node")
    void testGetNodeSpecsWithoutCache() {
        Satellite satellite = new Satellite("sat-002", "default", "compute-01", "10.0.0.2", "Linux 6.5", "0.3.0");

        Node node = provider.getNodeSpecs(satellite);

        assertEquals("sat-002", node.getId());
        assertEquals(1, node.getCpuCores());
        assertEquals(0, node.getMemoryGb());
        assertEquals(0, node.getGpuCount());
        assertNull(node.getGpuModel());
    }

    @Test
    @DisplayName("缓存应按 Satellite ID 隔离")
    void testCacheIsolationBySatelliteId() {
        HardwareSpecs specsA = HardwareSpecs.newBuilder()
                .setCpuCores(32)
                .setGpuCount(4)
                .setGpuModel("A100")
                .build();

        HardwareSpecs specsB = HardwareSpecs.newBuilder()
                .setCpuCores(96)
                .setGpuCount(8)
                .setGpuModel("H100")
                .build();

        provider.cacheHardwareSpecs("sat-a", specsA);
        provider.cacheHardwareSpecs("sat-b", specsB);

        Satellite satA = new Satellite("sat-a", "default", "node-a", "10.0.0.1", "Linux 6.5", "0.3.0");
        Satellite satB = new Satellite("sat-b", "default", "node-b", "10.0.0.2", "Linux 6.5", "0.3.0");

        Node nodeA = provider.getNodeSpecs(satA);
        Node nodeB = provider.getNodeSpecs(satB);

        assertEquals("A100", nodeA.getGpuModel());
        assertEquals(4, nodeA.getGpuCount());

        assertEquals("H100", nodeB.getGpuModel());
        assertEquals(8, nodeB.getGpuCount());
    }

    @Test
    @DisplayName("缓存不应接受 null 规格")
    void testCacheIgnoresNullSpecs() {
        provider.cacheHardwareSpecs("sat-003", null);

        Satellite satellite = new Satellite("sat-003", "default", "node-03", "10.0.0.3", "Linux 6.5", "0.3.0");
        Node node = provider.getNodeSpecs(satellite);

        // Should return defaults (no cached specs)
        assertEquals(1, node.getCpuCores());
        assertEquals(0, node.getGpuCount());
    }

    @Test
    @DisplayName("无 GPU 的计算节点应正确处理")
    void testComputeNodeWithoutGpu() {
        HardwareSpecs specs = HardwareSpecs.newBuilder()
                .setCpuCores(128)
                .setMemoryGb(1024)
                .setGpuCount(0)
                .build();

        provider.cacheHardwareSpecs("sat-cpu", specs);

        Satellite satellite = new Satellite("sat-cpu", "default", "cpu-node", "10.0.0.4", "Linux 6.5", "0.3.0");
        Node node = provider.getNodeSpecs(satellite);

        assertEquals(128, node.getCpuCores());
        assertEquals(1024, node.getMemoryGb());
        assertEquals(0, node.getGpuCount());
        assertEquals("", node.getGpuModel()); // Proto default for empty string
    }
}
