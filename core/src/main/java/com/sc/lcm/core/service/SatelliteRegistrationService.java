package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
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

    @WithTransaction
    public Uni<RegisterResponse> register(RegisterRequest request, boolean requireApproval) {
        if (requireApproval) {
            return DiscoveredDevice.findByIp(request.getIpAddress())
                    .flatMap(device -> {
                        if (device == null) {
                            log.warn("Registration rejected for {}: device unknown", request.getIpAddress());
                            return Uni.createFrom()
                                    .failure(new StatusRuntimeException(Status.UNAUTHENTICATED
                                            .withDescription("Device not discovered or approved")));
                        }
                        if (device.getStatus() != DiscoveryStatus.APPROVED
                                && device.getStatus() != DiscoveryStatus.MANAGED) {
                            log.warn("Registration rejected for {}: device status is {}", request.getIpAddress(),
                                    device.getStatus());
                            return Uni.createFrom()
                                    .failure(new StatusRuntimeException(
                                            Status.UNAUTHENTICATED.withDescription(
                                                    "Device not approved for registration. Current status: "
                                                            + device.getStatus())));
                        }
                        device.setStatus(DiscoveryStatus.MANAGED);
                        return persistSatelliteRegistration(request);
                    });
        }

        return persistSatelliteRegistration(request);
    }

    private Uni<RegisterResponse> persistSatelliteRegistration(RegisterRequest request) {
        String id = UUID.randomUUID().toString();
        Satellite satellite = new Satellite(
                id,
                request.getClusterId(),
                request.getHostname(),
                request.getIpAddress(),
                request.getOsVersion(),
                request.getAgentVersion());
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
