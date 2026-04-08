package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeResourceTest {

    @Test
    void nodeResponseExposesTopologyMetadata() {
        Satellite satellite = new Satellite();
        satellite.setId("node-1");
        satellite.setHostname("gpu-a100-01");
        satellite.setIpAddress("10.0.0.21");
        satellite.setOsVersion("ubuntu-24.04");
        satellite.setAgentVersion("1.2.3");
        satellite.setStatus("ONLINE");
        satellite.setCreatedAt(LocalDateTime.parse("2026-04-08T09:00:00"));
        satellite.setUpdatedAt(LocalDateTime.parse("2026-04-08T09:05:00"));

        Node node = new Node();
        node.setId("node-1");
        node.setCpuCores(96);
        node.setGpuCount(8);
        node.setGpuModel("H100");
        node.setMemoryGb(1024);
        node.setRackId("rack-a1");
        node.setZoneId("zone-apac-1");
        node.setGpuTopology("NVSwitch");
        node.setNvlinkBandwidthGbps(900);
        node.setIbFabricId("fabric-east-01");
        node.setBmcIp("10.0.1.21");
        node.setBmcMac("AA:BB:CC:DD:EE:FF");
        node.setSystemSerial("SN-001");
        node.setSystemModel("HGX-H100");

        NodeResource.NodeResponse response = NodeResource.NodeResponse.of(satellite, node, true, 321L);

        assertEquals("zone-apac-1", response.zoneId());
        assertEquals("rack-a1", response.rackId());
        assertEquals("NVSwitch", response.gpuTopology());
        assertEquals(900, response.nvlinkBandwidthGbps());
        assertEquals("fabric-east-01", response.ibFabricId());
        assertEquals("HGX-H100", response.systemModel());
        assertTrue(response.online());
    }
}
