package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryMethod;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.HardwareSpecs;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.RegisterResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SatelliteRegistrationServiceTest {

    @Inject
    SatelliteRegistrationService satelliteRegistrationService;

    @InjectMock
    RegistrationNodeSpecsProvider registrationNodeSpecs;

    @BeforeEach
    void resetState() throws Throwable {
        Mockito.reset(registrationNodeSpecs);
        Mockito.when(registrationNodeSpecs.persistNodeInCurrentTransaction(Mockito.anyString(), Mockito.any()))
                .thenReturn(Uni.createFrom().voidItem());

        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> Node.deleteAll()
                .chain(() -> Satellite.deleteAll())
                .chain(() -> DiscoveredDevice.deleteAll())
                .replaceWithVoid()));
    }

    @Test
    void registerRejectsUnknownDeviceWhenApprovalIsRequired() {
        RegisterRequest request = baseRequest("sat-unknown", "10.0.0.10");

        StatusRuntimeException exception = assertThrows(
                StatusRuntimeException.class,
                () -> VertxContextSupport.subscribeAndAwait(() -> satelliteRegistrationService.register(request, true)));

        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
        Mockito.verifyNoInteractions(registrationNodeSpecs);
    }

    @Test
    void registerPersistsSatelliteAndHardwareCallbacksWithoutApproval() throws Throwable {
        HardwareSpecs hardware = HardwareSpecs.newBuilder()
                .setCpuCores(64)
                .setMemoryGb(512)
                .setGpuCount(8)
                .setGpuModel("H100")
                .build();
        RegisterRequest request = RegisterRequest.newBuilder()
                .setHostname("sat-prod")
                .setIpAddress("10.0.0.20")
                .setOsVersion("Linux")
                .setAgentVersion("1.0.0")
                .setClusterId("cluster-west")
                .setHardware(hardware)
                .build();

        RegisterResponse response = VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(request, false));

        assertTrue(response.getSuccess());
        assertFalse(response.getAssignedId().isBlank());

        Satellite satellite = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> Satellite.findByIdReactive(response.getAssignedId())));

        assertNotNull(satellite);
        assertEquals("sat-prod", satellite.getHostname());
        assertEquals("cluster-west", satellite.getClusterId());
        assertEquals("10.0.0.20", satellite.getIpAddress());
        assertNotNull(satellite.getLastHeartbeat());

        Mockito.verify(registrationNodeSpecs).cacheHardwareSpecs(response.getAssignedId(), hardware);
        Mockito.verify(registrationNodeSpecs)
                .persistNodeInCurrentTransaction(response.getAssignedId(), hardware);
    }

    @Test
    void registerPromotesApprovedDeviceToManaged() throws Throwable {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.30");
        device.setHostname("rack-a-01");
        device.setDiscoveryMethod(DiscoveryMethod.AGENT);
        device.setStatus(DiscoveryStatus.APPROVED);
        device.setClaimStatus(ClaimStatus.CLAIMED);
        device.setAuthStatus(AuthStatus.PENDING);
        device.setCredentialProfileId("profile-1");

        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> device.persist().replaceWithVoid()));

        RegisterRequest request = baseRequest("sat-approved", "10.0.0.30");

        RegisterResponse response = VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(request, true));

        assertTrue(response.getSuccess());

        DiscoveredDevice managedDevice = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> DiscoveredDevice.findByIp("10.0.0.30")));
        Satellite satellite = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> Satellite.findByIdReactive(response.getAssignedId())));

        assertNotNull(managedDevice);
        assertEquals(DiscoveryStatus.MANAGED, managedDevice.getStatus());
        assertEquals(ClaimStatus.MANAGED, managedDevice.getClaimStatus());
        assertEquals(AuthStatus.AUTHENTICATED, managedDevice.getAuthStatus());
        assertNotNull(satellite);

        Mockito.verifyNoMoreInteractions(registrationNodeSpecs);
    }

    private RegisterRequest baseRequest(String hostname, String ipAddress) {
        return RegisterRequest.newBuilder()
                .setHostname(hostname)
                .setIpAddress(ipAddress)
                .setOsVersion("Linux")
                .setAgentVersion("1.0.0")
                .setClusterId("default")
                .build();
    }
}
