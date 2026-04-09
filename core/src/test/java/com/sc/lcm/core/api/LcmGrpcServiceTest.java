package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.grpc.DiscoveryRequest;
import com.sc.lcm.core.domain.Satellite;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LcmGrpcServiceTest {

    @Test
    void validateHeartbeatClusterRejectsMismatchedCluster() {
        Satellite satellite = new Satellite("sat-1", "west", "host", "127.0.0.1", "os", "agent");

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> LcmGrpcService.validateHeartbeatCluster(satellite, "east", "sat-1"));

        assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    }

    @Test
    void validateHeartbeatClusterTreatsBlankClusterAsDefault() {
        Satellite satellite = new Satellite("sat-1", null, "host", "127.0.0.1", "os", "agent");

        assertDoesNotThrow(() -> LcmGrpcService.validateHeartbeatCluster(satellite, "", "sat-1"));
    }

    @Test
    void redfishDiscoveryMethodMarksDeviceAsBmcCandidate() {
        DiscoveryRequest request = DiscoveryRequest.newBuilder()
                .setDiscoveredIp("127.0.0.1:18443")
                .setDiscoveryMethod("REDFISH")
                .build();
        DiscoveredDevice device = new DiscoveredDevice();

        LcmGrpcService.applyDiscoveryHints(device, request);

        assertEquals("BMC_ENABLED", device.getInferredType());
        assertEquals("127.0.0.1:18443", device.getBmcAddress());
    }

    @Test
    void regularDhcpDiscoveryDoesNotForceBmcHints() {
        DiscoveryRequest request = DiscoveryRequest.newBuilder()
                .setDiscoveredIp("10.0.0.25")
                .setDiscoveryMethod("DHCP_ACK")
                .build();
        DiscoveredDevice device = new DiscoveredDevice();

        LcmGrpcService.applyDiscoveryHints(device, request);

        assertNull(device.getInferredType());
        assertNull(device.getBmcAddress());
    }
}
