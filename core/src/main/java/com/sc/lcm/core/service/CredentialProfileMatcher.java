package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * 根据发现到的设备信息匹配首次纳管凭据策略。
 * 当前采用兼容式规则：设备类型 + 网段 + 厂商/型号提示。
 */
@ApplicationScoped
@Slf4j
public class CredentialProfileMatcher {

    public Uni<CredentialProfile> match(DiscoveredDevice device) {
        return CredentialProfile.findEnabledOrdered()
                .onItem().transform(profiles -> profiles.stream()
                        .filter(profile -> matches(profile, device))
                        .findFirst()
                        .orElse(null));
    }

    boolean matches(CredentialProfile profile, DiscoveredDevice device) {
        if (profile == null || device == null || !profile.isEnabled()) {
            return false;
        }

        if (isNotBlank(profile.getProtocol()) && !"REDFISH".equalsIgnoreCase(profile.getProtocol())) {
            return false;
        }

        if (isNotBlank(profile.getDeviceType())
                && !profile.getDeviceType().equalsIgnoreCase(device.getInferredType())) {
            return false;
        }

        if (isNotBlank(profile.getHostnamePattern())
                && !matchesPattern(device.getHostname(), profile.getHostnamePattern())) {
            return false;
        }

        if (isNotBlank(profile.getIpAddressPattern())
                && !matchesPattern(device.getIpAddress(), profile.getIpAddressPattern())) {
            return false;
        }

        if (isNotBlank(profile.getMacAddressPattern())
                && !matchesPattern(device.getMacAddress(), profile.getMacAddressPattern())) {
            return false;
        }

        if (isNotBlank(profile.getSubnetCidr()) && !ipInCidr(device.getIpAddress(), profile.getSubnetCidr())) {
            return false;
        }

        if (isNotBlank(profile.getVendorPattern())
                && !matchesPattern(device.getManufacturerHint(), profile.getVendorPattern())) {
            return false;
        }

        if (isNotBlank(profile.getModelPattern())
                && !matchesPattern(device.getModelHint(), profile.getModelPattern())) {
            return false;
        }

        return true;
    }

    static boolean matchesPattern(String value, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (PatternSyntaxException e) {
            return value.toLowerCase().contains(pattern.toLowerCase());
        }
    }

    static boolean ipInCidr(String ipAddress, String cidr) {
        if (ipAddress == null || ipAddress.isBlank() || cidr == null || cidr.isBlank()) {
            return false;
        }

        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            byte[] ip = InetAddress.getByName(ipAddress).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (ip.length != network.length) {
                return false;
            }

            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != network[i]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }

            int mask = -1 << (8 - remainingBits);
            return (ip[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException e) {
            log.warn("Failed to evaluate CIDR {} for IP {}", cidr, ipAddress, e);
            return false;
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
