package com.sc.lcm.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Node 实体单元测试
 * 验证 GPU 拓扑感知方法和基础属性
 */
class NodeTest {

    @Nested
    @DisplayName("GPU 拓扑感知")
    class GpuTopologyTests {

        @Test
        @DisplayName("NVLink 拓扑应返回 hasNvlink=true")
        void testHasNvlinkWithNVLink() {
            Node node = new Node("n1", 32, 128, 8, "A100");
            node.setGpuTopology("NVLink");
            assertTrue(node.hasNvlink());
        }

        @Test
        @DisplayName("NVSwitch 拓扑应返回 hasNvlink=true")
        void testHasNvlinkWithNVSwitch() {
            Node node = new Node("n2", 32, 128, 8, "H100");
            node.setGpuTopology("NVSwitch");
            assertTrue(node.hasNvlink());
        }

        @Test
        @DisplayName("PCIe 拓扑应返回 hasNvlink=false")
        void testHasNvlinkWithPCIe() {
            Node node = new Node("n3", 32, 128, 4, "A100");
            node.setGpuTopology("PCIe");
            assertFalse(node.hasNvlink());
        }

        @Test
        @DisplayName("无拓扑信息应返回 hasNvlink=false")
        void testHasNvlinkWithNull() {
            Node node = new Node("n4", 32, 128, 0, null);
            assertFalse(node.hasNvlink());
        }

        @Test
        @DisplayName("大小写不敏感的拓扑检测")
        void testHasNvlinkCaseInsensitive() {
            Node node = new Node("n5", 32, 128, 8, "A100");
            node.setGpuTopology("nvlink");
            assertTrue(node.hasNvlink());

            node.setGpuTopology("NVSWITCH");
            assertTrue(node.hasNvlink());
        }
    }

    @Nested
    @DisplayName("IB Fabric 亲和性")
    class IbFabricTests {

        @Test
        @DisplayName("相同 Fabric ID 应匹配")
        void testIsInIbFabric() {
            Node node = new Node("n1", 32, 128, 8, "A100");
            node.setIbFabricId("ib-switch-01");
            assertTrue(node.isInIbFabric("ib-switch-01"));
        }

        @Test
        @DisplayName("不同 Fabric ID 不应匹配")
        void testIsNotInIbFabric() {
            Node node = new Node("n1", 32, 128, 8, "A100");
            node.setIbFabricId("ib-switch-01");
            assertFalse(node.isInIbFabric("ib-switch-02"));
        }

        @Test
        @DisplayName("null Fabric ID 不应匹配")
        void testNullFabricId() {
            Node node = new Node("n1", 32, 128, 8, "A100");
            assertFalse(node.isInIbFabric("ib-switch-01"));
            assertFalse(node.isInIbFabric(null));
        }
    }

    @Nested
    @DisplayName("节点构造")
    class ConstructorTests {

        @Test
        @DisplayName("正确创建 GPU 节点")
        void testCreateGpuNode() {
            Node node = new Node("gpu-01", 64, 512, 8, "H100");
            assertEquals("gpu-01", node.getId());
            assertEquals(64, node.getCpuCores());
            assertEquals(512, node.getMemoryGb());
            assertEquals(8, node.getGpuCount());
            assertEquals("H100", node.getGpuModel());
        }

        @Test
        @DisplayName("正确创建纯计算节点")
        void testCreateComputeNode() {
            Node node = new Node("cpu-01", 128, 1024, 0, null);
            assertEquals(0, node.getGpuCount());
            assertNull(node.getGpuModel());
        }
    }
}
