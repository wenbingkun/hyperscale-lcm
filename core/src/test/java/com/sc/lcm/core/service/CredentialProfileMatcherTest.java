package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CredentialProfileMatcherTest {

    private final CredentialProfileMatcher matcher = new CredentialProfileMatcher();

    @Test
    @DisplayName("应匹配同网段同设备类型的凭据档案")
    void shouldMatchProfileBySubnetAndDeviceType() {
        CredentialProfile profile = new CredentialProfile();
        profile.setEnabled(true);
        profile.setProtocol("REDFISH");
        profile.setDeviceType("BMC_ENABLED");
        profile.setSubnetCidr("10.0.0.0/24");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.45");
        device.setInferredType("BMC_ENABLED");

        assertTrue(matcher.matches(profile, device));
    }

    @Test
    @DisplayName("网段不匹配时不应命中凭据档案")
    void shouldRejectProfileWhenSubnetDiffers() {
        CredentialProfile profile = new CredentialProfile();
        profile.setEnabled(true);
        profile.setProtocol("REDFISH");
        profile.setSubnetCidr("10.0.1.0/24");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setIpAddress("10.0.0.45");
        device.setInferredType("BMC_ENABLED");

        assertFalse(matcher.matches(profile, device));
    }

    @Test
    @DisplayName("厂商提示应支持正则匹配")
    void shouldMatchVendorPattern() {
        CredentialProfile profile = new CredentialProfile();
        profile.setEnabled(true);
        profile.setVendorPattern("dell|idrac");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setManufacturerHint("Dell Inc.");

        assertTrue(matcher.matches(profile, device));
    }

    @Test
    @DisplayName("应支持 hostname / IP / MAC 的精确匹配键")
    void shouldMatchExactImportedKeys() {
        CredentialProfile profile = new CredentialProfile();
        profile.setEnabled(true);
        profile.setHostnamePattern("^bmc-rack-a-01$");
        profile.setIpAddressPattern("^10\\.10\\.0\\.50$");
        profile.setMacAddressPattern("^00:11:22:33:44:55$");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setHostname("bmc-rack-a-01");
        device.setIpAddress("10.10.0.50");
        device.setMacAddress("00:11:22:33:44:55");

        assertTrue(matcher.matches(profile, device));
    }

    @Test
    @DisplayName("CIDR 工具方法应识别同子网地址")
    void shouldRecognizeIpWithinCidr() {
        assertTrue(CredentialProfileMatcher.ipInCidr("192.168.10.5", "192.168.10.0/24"));
        assertFalse(CredentialProfileMatcher.ipInCidr("192.168.11.5", "192.168.10.0/24"));
    }
}
