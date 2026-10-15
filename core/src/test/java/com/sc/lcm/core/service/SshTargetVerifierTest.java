package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import com.sc.lcm.core.domain.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshTargetVerifierTest {

    private Job job() {
        Job job = new Job("job-1", 1, 1, 0, null);
        job.setTargetDeviceId("dev-1");
        job.setTargetHost("10.0.0.5");
        return job;
    }

    private DiscoveredDevice device(String ip, DiscoveryStatus status) {
        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress(ip);
        device.setStatus(status);
        return device;
    }

    @Test
    void unchangedApprovedTargetPasses() {
        assertNull(SshTargetVerifier.describeMismatch(job(), device("10.0.0.5", DiscoveryStatus.MANAGED), "10.0.0.5"));
        assertNull(SshTargetVerifier.describeMismatch(job(), device("10.0.0.5", DiscoveryStatus.APPROVED), "10.0.0.5"));
    }

    @Test
    void missingRejectedOrMovedTargetIsRefused() {
        assertTrue(SshTargetVerifier.describeMismatch(job(), null, "10.0.0.5").contains("no longer exists"));
        assertTrue(SshTargetVerifier.describeMismatch(job(), device("10.0.0.5", DiscoveryStatus.REJECTED), "10.0.0.5")
                .contains("no longer approved"));
        assertTrue(SshTargetVerifier.describeMismatch(job(), device("10.0.0.9", DiscoveryStatus.MANAGED), "10.0.0.5")
                .contains("address changed"));
    }

    @Test
    void payloadHostMustMatchRecordedTarget() {
        assertTrue(SshTargetVerifier.describeMismatch(job(), device("10.0.0.5", DiscoveryStatus.MANAGED), "10.0.0.66")
                .contains("payload host"));
        assertTrue(SshTargetVerifier.describeMismatch(job(), device("10.0.0.5", DiscoveryStatus.MANAGED), null)
                .contains("payload host"));
    }
}
