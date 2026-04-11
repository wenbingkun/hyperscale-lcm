package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.RedfishAuthMode;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 执行首次 Redfish claim。
 * 当前阶段先验证 BMC 登录是否成功，并回传真实厂商/型号信息。
 */
@ApplicationScoped
@Slf4j
public class RedfishClaimExecutor {

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    RedfishTemplateCatalog redfishTemplateCatalog;

    @Inject
    RedfishTransport redfishTransport;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.claim.redfish.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.redfish.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    @ConfigProperty(name = "lcm.claim.redfish.insecure", defaultValue = "true")
    boolean insecure = true;

    @ConfigProperty(name = "lcm.claim.redfish.auth-mode-default", defaultValue = "SESSION_PREFERRED")
    String defaultAuthMode = RedfishAuthMode.SESSION_PREFERRED.name();

    ProbeTransport transport;

    public Uni<ClaimExecutionResult> execute(DiscoveredDevice device, CredentialProfile profile) {
        if (device == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(null, null, null,
                    "Device not found.", null, null));
        }
        if (profile == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(resolveEndpoint(device), null, null,
                    "Credential profile not found.", null, null));
        }

        String endpoint = resolveEndpoint(device);
        if (endpoint == null) {
            return Uni.createFrom().item(ClaimExecutionResult.failure(null, null, null,
                    "BMC endpoint is missing on the discovered device.", null, null));
        }

        return secretRefResolver.resolve(profile)
                .onItem().transformToUni(credentials -> {
                    if (!credentials.isReady()) {
                        return Uni.createFrom().item(ClaimExecutionResult.failure(
                                endpoint,
                                credentials.getCredentialSource(),
                                null,
                                "Claim blocked because secret refs are not ready. " + credentials.getMessage(),
                                null,
                                null));
                    }

                    ProbeRequest request = new ProbeRequest(
                            endpoint,
                            credentials.getUsername().getValue(),
                            credentials.getPassword().getValue(),
                            insecure,
                            connectTimeoutMs,
                            readTimeoutMs,
                            resolveAuthMode(device, profile));

                    ProbeTransport activeTransport = transport != null ? transport : this::probeBlocking;

                    return Uni.createFrom().item(() -> {
                                try {
                                    return activeTransport.probe(request);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                            .map(response -> {
                                String recommendedTemplate = preferConfiguredTemplate(
                                        profile.getRedfishTemplate(),
                                        redfishTemplateCatalog.recommend(response.manufacturer(), response.model())
                                                .map(RedfishTemplateCatalog.TemplateMetadata::name)
                                                .orElse(null));
                                String message = "Redfish authentication validated for " + endpoint + ".";
                                if (recommendedTemplate != null && !recommendedTemplate.isBlank()) {
                                    message += " Recommended template '" + recommendedTemplate + "'.";
                                }
                                return ClaimExecutionResult.success(
                                        endpoint,
                                        credentials.getCredentialSource(),
                                        response.manufacturer(),
                                        response.model(),
                                        recommendedTemplate,
                                        message,
                                        response.authMode(),
                                        response.capabilities());
                            })
                            .onFailure().recoverWithItem(error -> {
                                RedfishTransport.RedfishTransportException transportException = findTransportException(error);
                                Throwable root = rootCause(error);
                                String failureCode = transportException != null
                                        ? transportException.failureCode()
                                        : extractFailureCode(root);
                                String failureMessage = transportException != null
                                        ? transportException.getMessage()
                                        : rootCauseMessage(root);
                                log.warn("Redfish claim failed for {}", endpoint, error);
                                return ClaimExecutionResult.failure(
                                        endpoint,
                                        credentials.getCredentialSource(),
                                        failureCode,
                                        "Redfish claim failed for " + endpoint + ": " + failureMessage,
                                        failureMessage,
                                        null);
                            });
                });
    }

    ProbeResponse probeBlocking(ProbeRequest request) throws Exception {
        RedfishTransport.InspectionResult inspection = redfishTransport.inspect(new RedfishTransport.RequestOptions(
                request.endpoint(),
                request.username(),
                request.password(),
                request.insecure(),
                request.connectTimeoutMs(),
                request.readTimeoutMs(),
                request.authMode()));
        return new ProbeResponse(
                inspection.manufacturer(),
                inspection.model(),
                inspection.authMode(),
                inspection.capabilities());
    }

    static String resolveEndpoint(DiscoveredDevice device) {
        if (device == null) {
            return null;
        }
        String candidate = device.getBmcAddress();
        if (candidate == null || candidate.isBlank()) {
            candidate = device.getIpAddress();
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
            candidate = "https://" + candidate;
        }
        return candidate.replaceAll("/+$", "");
    }

    static String absoluteUrl(String endpoint, String path) {
        return RedfishTransport.absoluteUrl(endpoint, path);
    }

    private RedfishAuthMode resolveAuthMode(DiscoveredDevice device, CredentialProfile profile) {
        String rawValue = device != null && device.getRedfishAuthModeOverride() != null
                ? device.getRedfishAuthModeOverride()
                : profile.getRedfishAuthMode();
        return RedfishAuthMode.parse(rawValue, RedfishAuthMode.parse(defaultAuthMode, RedfishAuthMode.SESSION_PREFERRED));
    }

    private static String preferConfiguredTemplate(String configured, String recommended) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return recommended;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RedfishTransport.RedfishTransportException findTransportException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RedfishTransport.RedfishTransportException transportException) {
                return transportException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String extractFailureCode(Throwable error) {
        if (error instanceof RedfishTransport.RedfishTransportException transportException) {
            return transportException.failureCode();
        }
        return error.getClass().getSimpleName();
    }

    private static String rootCauseMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    @FunctionalInterface
    interface ProbeTransport {
        ProbeResponse probe(ProbeRequest request) throws Exception;
    }

    public record ClaimExecutionResult(
            boolean success,
            String endpoint,
            String credentialSource,
            String manufacturer,
            String model,
            String recommendedTemplate,
            String message,
            String authMode,
            String authFailureCode,
            String authFailureReason,
            Map<String, Object> bmcCapabilities) {

        static ClaimExecutionResult success(
                String endpoint,
                String credentialSource,
                String manufacturer,
                String model,
                String recommendedTemplate,
                String message,
                String authMode,
                Map<String, Object> bmcCapabilities) {
            return new ClaimExecutionResult(
                    true,
                    endpoint,
                    credentialSource,
                    manufacturer,
                    model,
                    recommendedTemplate,
                    message,
                    authMode,
                    null,
                    null,
                    bmcCapabilities);
        }

        static ClaimExecutionResult failure(
                String endpoint,
                String credentialSource,
                String authFailureCode,
                String message,
                String authFailureReason,
                Map<String, Object> bmcCapabilities) {
            return new ClaimExecutionResult(
                    false,
                    endpoint,
                    credentialSource,
                    null,
                    null,
                    null,
                    message,
                    null,
                    authFailureCode,
                    authFailureReason,
                    bmcCapabilities);
        }
    }

    record ProbeRequest(
            String endpoint,
            String username,
            String password,
            boolean insecure,
            int connectTimeoutMs,
            int readTimeoutMs,
            RedfishAuthMode authMode) {
    }

    record ProbeResponse(
            String manufacturer,
            String model,
            String authMode,
            Map<String, Object> capabilities) {
    }
}
