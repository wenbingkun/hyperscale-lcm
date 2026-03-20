package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.DiscoveredDevice.AuthStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.ClaimStatus;
import com.sc.lcm.core.domain.DiscoveredDevice.DiscoveryStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 将“发现设备”与“首次凭据接管”之间补上一个明确的状态机。
 * 当前版本先做自动规划，不在这里执行真实的 BMC 登录。
 */
@ApplicationScoped
@Slf4j
public class DeviceClaimPlanner {

    @Inject
    CredentialProfileMatcher matcher;

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    RedfishTemplateCatalog redfishTemplateCatalog;

    public Uni<DiscoveredDevice> plan(DiscoveredDevice device) {
        if (device == null) {
            return Uni.createFrom().nullItem();
        }

        if (device.getStatus() == DiscoveryStatus.MANAGED) {
            device.setClaimStatus(ClaimStatus.MANAGED);
            if (device.getCredentialProfileId() != null && !device.getCredentialProfileId().isBlank()) {
                device.setAuthStatus(AuthStatus.AUTHENTICATED);
            }
            return Uni.createFrom().item(device);
        }

        if (!isBmcCandidate(device)) {
            applyNonBmcState(device);
            return Uni.createFrom().item(device);
        }

        String recommendedTemplate = redfishTemplateCatalog.recommend(device)
                .map(RedfishTemplateCatalog.TemplateMetadata::name)
                .orElse(null);

        return matcher.match(device)
                .onItem().transformToUni(profile -> {
                    if (profile != null) {
                        return secretRefResolver.resolve(profile)
                                .map(resolvedCredentials -> {
                                    applyMatchedProfile(device, profile, resolvedCredentials, recommendedTemplate);
                                    logPlannedState(device);
                                    return device;
                                });
                    }

                    applyMissingProfile(device, recommendedTemplate);
                    logPlannedState(device);
                    return Uni.createFrom().item(device);
                });
    }

    static boolean isBmcCandidate(DiscoveredDevice device) {
        if (device == null) {
            return false;
        }
        if ("BMC_ENABLED".equalsIgnoreCase(device.getInferredType())) {
            return true;
        }
        String ports = device.getOpenPorts();
        return ports != null && (ports.contains("623") || ports.contains("443"));
    }

    static void applyMatchedProfile(
            DiscoveredDevice device,
            CredentialProfile profile,
            SecretRefResolver.ResolvedCredentialMaterial resolvedCredentials) {
        applyMatchedProfile(device, profile, resolvedCredentials, null);
    }

    static void applyMatchedProfile(
            DiscoveredDevice device,
            CredentialProfile profile,
            SecretRefResolver.ResolvedCredentialMaterial resolvedCredentials,
            String recommendedTemplate) {
        device.setCredentialProfileId(profile.getId());
        device.setCredentialProfileName(profile.getName());
        device.setCredentialSource(resolvedCredentials.getCredentialSource());
        device.setRecommendedRedfishTemplate(preferConfiguredTemplate(profile.getRedfishTemplate(), recommendedTemplate));

        if (!profile.isAutoClaim()) {
            device.setAuthStatus(resolvedCredentials.isReady() ? AuthStatus.PROFILE_MATCHED : AuthStatus.AUTH_PENDING);
            device.setClaimStatus(ClaimStatus.DISCOVERED);
            device.setClaimMessage("Matched credential profile '" + profile.getName()
                    + "', but auto-claim is disabled. " + resolvedCredentials.getMessage());
            return;
        }

        if (resolvedCredentials.isReady()) {
            device.setAuthStatus(AuthStatus.PROFILE_MATCHED);
            device.setClaimStatus(ClaimStatus.READY_TO_CLAIM);
            device.setClaimMessage(resolvedCredentials.getMessage());
            return;
        }

        device.setAuthStatus(AuthStatus.AUTH_PENDING);
        device.setClaimStatus(ClaimStatus.DISCOVERED);
        device.setClaimMessage(resolvedCredentials.getMessage());
    }

    static void applyMissingProfile(DiscoveredDevice device) {
        applyMissingProfile(device, null);
    }

    static void applyMissingProfile(DiscoveredDevice device, String recommendedTemplate) {
        device.setAuthStatus(AuthStatus.AUTH_PENDING);
        device.setClaimStatus(ClaimStatus.DISCOVERED);
        device.setCredentialProfileId(null);
        device.setCredentialProfileName(null);
        device.setCredentialSource(null);
        device.setRecommendedRedfishTemplate(recommendedTemplate);
        if (recommendedTemplate != null && !recommendedTemplate.isBlank()) {
            device.setClaimMessage("BMC detected but no credential profile matched. Recommended Redfish template '"
                    + recommendedTemplate + "'. Manual bootstrap or CMDB/Vault import required.");
            return;
        }
        device.setClaimMessage("BMC detected but no credential profile matched. Manual bootstrap or CMDB/Vault import required.");
    }

    static void applyNonBmcState(DiscoveredDevice device) {
        device.setAuthStatus(AuthStatus.PENDING);
        device.setClaimStatus(ClaimStatus.DISCOVERED);
        device.setCredentialProfileId(null);
        device.setCredentialProfileName(null);
        device.setCredentialSource(null);
        device.setRecommendedRedfishTemplate(null);
        if (device.getClaimMessage() == null || device.getClaimMessage().startsWith("Matched credential profile")
                || device.getClaimMessage().startsWith("BMC detected")) {
            device.setClaimMessage("Device has not been classified as a BMC candidate yet.");
        }
    }

    private static String preferConfiguredTemplate(String configuredTemplate, String recommendedTemplate) {
        if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            return configuredTemplate;
        }
        return recommendedTemplate;
    }

    private static void logPlannedState(DiscoveredDevice device) {
        log.debug("Planned claim state for {} -> auth={}, claim={}, profile={}",
                device.getIpAddress(), device.getAuthStatus(), device.getClaimStatus(),
                device.getCredentialProfileName());
    }
}
