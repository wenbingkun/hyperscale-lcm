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

    @Test
    void registerBindsCertificateAndRecoversIdentityAfterIpChange() throws Throwable {
        persistDevice("10.0.1.1", DiscoveryStatus.APPROVED);

        RegisterResponse first = VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(baseRequest("sat-a", "10.0.1.1"), true, "fp-a"));
        RegisterResponse second = VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(baseRequest("sat-a", "10.0.9.9"), true, "fp-a"));

        assertEquals(first.getAssignedId(), second.getAssignedId());
        DiscoveredDevice device = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> DiscoveredDevice.findBySatelliteCertFingerprint("fp-a")));
        assertEquals("10.0.9.9", device.getIpAddress());
        assertEquals(DiscoveryStatus.MANAGED, device.getStatus());
        Long satellites = VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> Satellite.count()));
        assertEquals(1L, satellites);
    }

    @Test
    void registerRejectsOtherCertificateClaimingBoundDeviceIp() throws Throwable {
        persistDevice("10.0.2.1", DiscoveryStatus.APPROVED);
        VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(baseRequest("sat-a", "10.0.2.1"), true, "fp-a"));

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> VertxContextSupport.subscribeAndAwait(
                        () -> satelliteRegistrationService.register(baseRequest("evil", "10.0.2.1"), true, "fp-b")));

        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
    }

    @Test
    void registerRejectsBoundCertificateWhenDeviceIsNoLongerApproved() throws Throwable {
        persistDevice("10.0.3.1", DiscoveryStatus.APPROVED);
        VertxContextSupport.subscribeAndAwait(
                () -> satelliteRegistrationService.register(baseRequest("sat-a", "10.0.3.1"), true, "fp-a"));
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(
                () -> DiscoveredDevice.<DiscoveredDevice>find("ipAddress", "10.0.3.1").firstResult()
                        .invoke(d -> d.setStatus(DiscoveryStatus.REJECTED)).replaceWithVoid()));

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> VertxContextSupport.subscribeAndAwait(
                        () -> satelliteRegistrationService.register(baseRequest("sat-a", "10.0.3.1"), true, "fp-a")));

        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
    }

    @Test
    void registerRejectsUnapprovedDeviceEvenWithValidCertificate() {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> VertxContextSupport.subscribeAndAwait(
                        () -> satelliteRegistrationService.register(baseRequest("sat-x", "10.0.4.1"), true, "fp-x")));

        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
    }

    private void persistDevice(String ip, DiscoveryStatus status) throws Throwable {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress(ip);
        device.setDiscoveryMethod(DiscoveryMethod.AGENT);
        device.setStatus(status);
        device.setClaimStatus(ClaimStatus.CLAIMED);
        device.setAuthStatus(AuthStatus.PENDING);
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> device.persist().replaceWithVoid()));
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
