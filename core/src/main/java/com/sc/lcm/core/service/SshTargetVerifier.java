package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Job;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;

/**
 * Re-checks, right before dispatch, that an SSH task job still points at the device it was submitted for:
 * the device must still be approved and must still have the address recorded on the job and in the payload.
 */
@ApplicationScoped
public class SshTargetVerifier {

    @Inject
    ObjectMapper objectMapper;

    /** Emits null when the target is unchanged, otherwise a human-readable reason. */
    public Uni<String> findMismatch(Job job) {
        return Panache.withSession(() -> DiscoveredDevice.<DiscoveredDevice>findById(job.getTargetDeviceId()))
                .map(device -> describeMismatch(job, device, payloadHost(job.getExecutionPayload())));
    }

    static String describeMismatch(Job job, DiscoveredDevice device, String payloadHost) {
        if (device == null) {
            return "Target device " + job.getTargetDeviceId() + " no longer exists";
        }
        if (device.getStatus() != DiscoveryStatus.APPROVED && device.getStatus() != DiscoveryStatus.MANAGED) {
            return "Target device is no longer approved (status: " + device.getStatus() + ")";
        }
        if (!Objects.equals(device.getIpAddress(), job.getTargetHost())) {
            return "Target device address changed since submission (" + job.getTargetHost() + " -> "
                    + device.getIpAddress() + ")";
        }
        if (!Objects.equals(payloadHost, job.getTargetHost())) {
            return "Job payload host does not match the recorded target";
        }
        return null;
    }

    private String payloadHost(String payload) {
        try {
            JsonNode host = objectMapper.readTree(payload).get("host");
            return host == null ? null : host.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
