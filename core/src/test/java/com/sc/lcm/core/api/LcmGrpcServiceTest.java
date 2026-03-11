package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Satellite;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
