package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.RegisterRequest;
import com.sc.lcm.core.grpc.RegisterResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class SatelliteRegistrationService {

    @Inject
    RegistrationNodeSpecsProvider registrationNodeSpecs;

    public Uni<RegisterResponse> register(RegisterRequest request, boolean requireApproval) {
        return register(request, requireApproval, null);
    }

    /**
     * @param peerFingerprint SHA-256 of the client certificate verified in the TLS handshake (null when the
     *                        connection carried none); this, not the self-reported IP, is the Satellite identity.
     */
    @WithTransaction
    public Uni<RegisterResponse> register(RegisterRequest request, boolean requireApproval, String peerFingerprint) {
        if (!requireApproval) {
            return persistSatelliteRegistration(request, peerFingerprint);
        }
        if (peerFingerprint == null) {
            return findDeviceByIp(request, null);
        }
        return DiscoveredDevice.findBySatelliteCertFingerprint(peerFingerprint)
                .flatMap(bound -> bound != null
                        ? admit(bound, request, peerFingerprint)
                        : findDeviceByIp(request, peerFingerprint));
    }

    private Uni<RegisterResponse> findDeviceByIp(RegisterRequest request, String peerFingerprint) {
        return DiscoveredDevice.findByIp(request.getIpAddress())
                .flatMap(device -> {
                    if (device == null) {
                        log.warn("Registration rejected for {}: device unknown", request.getIpAddress());
                        return rejected("Device not discovered or approved");
                    }
                    String boundTo = device.getSatelliteCertFingerprint();
                    if (peerFingerprint != null && boundTo != null && !boundTo.equals(peerFingerprint)) {
                        log.warn("Registration rejected for {}: device is bound to a different certificate",
                                request.getIpAddress());
                        return rejected("Device is bound to a different Satellite certificate");
                    }
                    return admit(device, request, peerFingerprint);
                });
    }

    private Uni<RegisterResponse> admit(DiscoveredDevice device, RegisterRequest request, String peerFingerprint) {
        if (device.getStatus() != DiscoveryStatus.APPROVED && device.getStatus() != DiscoveryStatus.MANAGED) {
            log.warn("Registration rejected for {}: device status is {}", request.getIpAddress(), device.getStatus());
            return rejected("Device not approved for registration. Current status: " + device.getStatus());
        }
        device.setStatus(DiscoveryStatus.MANAGED);
        device.setClaimStatus(ClaimStatus.MANAGED);
        if (device.getCredentialProfileId() != null && !device.getCredentialProfileId().isBlank()) {
            device.setAuthStatus(AuthStatus.AUTHENTICATED);
        }
        if (peerFingerprint != null) {
            device.setSatelliteCertFingerprint(peerFingerprint);
            if (request.getIpAddress() != null && !request.getIpAddress().isBlank()) {
                device.setIpAddress(request.getIpAddress());
            }
        }
        return persistSatelliteRegistration(request, peerFingerprint);
    }

    private static Uni<RegisterResponse> rejected(String message) {
        return Uni.createFrom().failure(new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription(message)));
    }

    private Uni<RegisterResponse> persistSatelliteRegistration(RegisterRequest request, String peerFingerprint) {
        if (peerFingerprint == null) {
            return persistSatellite(request, null, null);
        }
        return Satellite.findByCertFingerprint(peerFingerprint)
                .flatMap(existing -> persistSatellite(request, peerFingerprint, existing));
    }

    private Uni<RegisterResponse> persistSatellite(RegisterRequest request, String peerFingerprint, Satellite existing) {
        Satellite satellite = existing != null ? existing : new Satellite(
                UUID.randomUUID().toString(),
                request.getClusterId(),
                request.getHostname(),
                request.getIpAddress(),
                request.getOsVersion(),
                request.getAgentVersion());
        String id = satellite.getId();
        if (existing != null) {
            log.info("Satellite {} reconnected with the same certificate; reusing identity", id);
            satellite.setClusterId((request.getClusterId() == null || request.getClusterId().isBlank())
                    ? "default" : request.getClusterId());
            satellite.setHostname(request.getHostname());
            satellite.setIpAddress(request.getIpAddress());
            satellite.setOsVersion(request.getOsVersion());
            satellite.setAgentVersion(request.getAgentVersion());
            satellite.setStatus("ONLINE");
        }
        satellite.setCertFingerprint(peerFingerprint);
        satellite.setLastHeartbeat(LocalDateTime.now());

        if (request.hasHardware()) {
            registrationNodeSpecs.cacheHardwareSpecs(id, request.getHardware());
        }

        return satellite.<Satellite>persist()
                .replaceWithVoid()
                .flatMap(v -> {
                    if (request.hasHardware()) {
                        return registrationNodeSpecs.persistNodeInCurrentTransaction(id, request.getHardware());
                    }
                    return Uni.createFrom().voidItem();
                })
                .map(v -> {
                    log.info("Registered satellite with ID: {} (Node synced: {})", id, request.hasHardware());
                    return RegisterResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("Registration Successful")
                            .setAssignedId(id)
                            .build();
                });
    }
}
