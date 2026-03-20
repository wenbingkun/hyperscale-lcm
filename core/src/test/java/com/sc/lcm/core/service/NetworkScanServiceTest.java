package com.sc.lcm.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkScanServiceTest {

    @Test
    @DisplayName("默认扫描端口应包含 Redfish 443")
    void defaultPortsShouldIncludeRedfishHttps() {
        NetworkScanService service = new NetworkScanService();

        assertTrue(service.parsePorts(null).contains(443));
        assertTrue(service.parsePorts(null).contains(623));
    }
}
