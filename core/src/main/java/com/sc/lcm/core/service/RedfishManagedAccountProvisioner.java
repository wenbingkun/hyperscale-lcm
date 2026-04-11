package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.RedfishAuthMode;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 在首次 Redfish claim 成功后，按标准 Redfish AccountService 创建或收敛平台托管账号。
 * 当前仅覆盖标准路径，不做厂商 OEM 动作扩展。
 */
@ApplicationScoped
@Slf4j
public class RedfishManagedAccountProvisioner {

    private static final String ACCOUNT_SERVICE_PATH = RedfishTransport.ACCOUNT_SERVICE_PATH;
    private static final String FALLBACK_ACCOUNTS_PATH = ACCOUNT_SERVICE_PATH + "/Accounts";

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RedfishTransport redfishTransport;

    @ConfigProperty(name = "lcm.claim.redfish.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.redfish.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    @ConfigProperty(name = "lcm.claim.redfish.insecure", defaultValue = "true")
    boolean insecure = true;

    @ConfigProperty(name = "lcm.claim.redfish.managed-account.default-role-id", defaultValue = "Administrator")
    String defaultRoleId = "Administrator";

    @ConfigProperty(name = "lcm.claim.redfish.managed-account.rotate-on-claim", defaultValue = "true")
    boolean rotateOnClaim = true;

    @ConfigProperty(name = "lcm.claim.redfish.auth-mode-default", defaultValue = "SESSION_PREFERRED")
    String defaultAuthMode = RedfishAuthMode.SESSION_PREFERRED.name();

    ProvisionTransport transport;

    public Uni<ManagedAccountProvisionResult> provision(DiscoveredDevice device, CredentialProfile profile) {
        if (profile == null) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.notEnabled("Credential profile not found."));
        }
        if (!profile.isManagedAccountEnabled()) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.notEnabled(
                    "Managed account provisioning is disabled for credential profile '" + profile.getName() + "'."));
        }

        String endpoint = RedfishClaimExecutor.resolveEndpoint(device);
        if (endpoint == null) {
            return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                    true,
                    null,
                    null,
                    null,
                    "BMC endpoint is missing on the discovered device.",
                    null,
                    null));
        }

        return secretRefResolver.resolve(profile)
                .onItem().transformToUni(bootstrapCredentials -> {
                    if (!bootstrapCredentials.isReady()) {
                        return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                                true,
                                endpoint,
                                null,
                                null,
                                "Managed account provisioning blocked because bootstrap claim credentials are not ready. "
                                        + bootstrapCredentials.getMessage(),
                                null,
                                null));
                    }

                    return secretRefResolver.resolveManagedAccount(profile)
                            .onItem().transformToUni(managedCredentials -> {
                                if (!managedCredentials.isReady()) {
                                    return Uni.createFrom().item(ManagedAccountProvisionResult.failure(
                                            true,
                                            endpoint,
                                            null,
                                            resolveRoleId(profile),
                                            "Managed account provisioning blocked because managed account secret refs are not ready. "
                                                    + managedCredentials.getMessage(),
                                            null,
                                            null));
                                }

                                ProvisionRequest request = new ProvisionRequest(
                                        endpoint,
                                        bootstrapCredentials.getUsername().getValue(),
                                        bootstrapCredentials.getPassword().getValue(),
                                        managedCredentials.getUsername().getValue(),
                                        managedCredentials.getPassword().getValue(),
                                        resolveRoleId(profile),
                                        insecure,
                                        connectTimeoutMs,
                                        readTimeoutMs,
                                        resolveAuthMode(device, profile));

                                ProvisionTransport activeTransport = transport != null ? transport : this::provisionBlocking;
                                return Uni.createFrom().item(() -> {
                                            try {
                                                return activeTransport.provision(request);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                        .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                                        .onFailure().recoverWithItem(error -> {
                                            RedfishTransport.RedfishTransportException transportException = findTransportException(error);
                                            Throwable root = rootCause(error);
                                            String rootMessage = transportException != null
                                                    ? transportException.getMessage()
                                                    : rootCauseMessage(root);
                                            String failureCode = transportException != null
                                                    ? transportException.failureCode()
                                                    : extractFailureCode(root);
                                            log.warn("Managed BMC account provisioning failed for {}", endpoint, error);
                                            return ManagedAccountProvisionResult.failure(
                                                    true,
                                                    endpoint,
                                                    request.managedUsername(),
                                                    request.roleId(),
                                                    "Managed BMC account provisioning failed for " + endpoint + ": " + rootMessage,
                                                    request.authMode().name(),
                                                    failureCode);
                                        });
                            });
                });
    }

    ManagedAccountProvisionResult provisionBlocking(ProvisionRequest request) throws Exception {
        RedfishTransport.AuthContext authContext = redfishTransport.open(new RedfishTransport.RequestOptions(
                request.endpoint(),
                request.bootstrapUsername(),
                request.bootstrapPassword(),
                request.insecure(),
                request.connectTimeoutMs(),
                request.readTimeoutMs(),
                request.authMode()));
        Map<String, Object> accountService = redfishTransport.getJson(authContext, ACCOUNT_SERVICE_PATH, true);
        String accountsUri = extractUri(accountService, "Accounts");
        if (accountsUri == null || accountsUri.isBlank()) {
            accountsUri = FALLBACK_ACCOUNTS_PATH;
        }

        Map<String, Object> accountsCollection = redfishTransport.getJson(authContext, accountsUri, true);
        String existingAccountUri = findExistingAccountUri(authContext, accountsCollection, request.managedUsername());
        if (existingAccountUri != null) {
            if (!rotateOnClaim) {
                return ManagedAccountProvisionResult.success(
                        true,
                        request.endpoint(),
                        request.managedUsername(),
                        request.roleId(),
                        "Managed BMC account '" + request.managedUsername() + "' already exists on " + request.endpoint() + ".",
                        authContext.actualAuthMode());
            }

            Map<String, Object> patchPayload = new LinkedHashMap<>();
            patchPayload.put("Password", request.managedPassword());
            patchPayload.put("RoleId", request.roleId());
            patchPayload.put("Enabled", true);
            redfishTransport.writeJson(authContext, "PATCH", existingAccountUri, patchPayload, false);
            return ManagedAccountProvisionResult.success(
                    true,
                    request.endpoint(),
                    request.managedUsername(),
                    request.roleId(),
                    "Managed BMC account '" + request.managedUsername() + "' was reconciled on " + request.endpoint() + ".",
                    authContext.actualAuthMode());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserName", request.managedUsername());
        payload.put("Password", request.managedPassword());
        payload.put("RoleId", request.roleId());
        payload.put("Enabled", true);
        redfishTransport.writeJson(authContext, "POST", accountsUri, payload, false);
        return ManagedAccountProvisionResult.success(
                true,
                request.endpoint(),
                request.managedUsername(),
                request.roleId(),
                "Managed BMC account '" + request.managedUsername() + "' is ready on " + request.endpoint() + ".",
                authContext.actualAuthMode());
    }

    private String findExistingAccountUri(
            RedfishTransport.AuthContext authContext,
            Map<String, Object> collection,
            String targetUsername) throws Exception {
        Object rawMembers = collection.get("Members");
        if (!(rawMembers instanceof List<?> members)) {
            return null;
        }

        for (Object member : members) {
            if (!(member instanceof Map<?, ?> memberMap)) {
                continue;
            }
            Object uri = memberMap.get("@odata.id");
            if (!(uri instanceof String accountUri) || accountUri.isBlank()) {
                continue;
            }

            Map<String, Object> accountDocument = redfishTransport.getJson(authContext, accountUri, true);
            String username = extractString(accountDocument, "UserName");
            if (targetUsername.equals(username)) {
                return accountUri;
            }
        }
        return null;
    }

    private String resolveRoleId(CredentialProfile profile) {
        if (profile.getManagedAccountRoleId() != null && !profile.getManagedAccountRoleId().isBlank()) {
            return profile.getManagedAccountRoleId();
        }
        return defaultRoleId;
    }

    private RedfishAuthMode resolveAuthMode(DiscoveredDevice device, CredentialProfile profile) {
        String rawValue = device != null && device.getRedfishAuthModeOverride() != null
                ? device.getRedfishAuthModeOverride()
                : profile.getRedfishAuthMode();
        return RedfishAuthMode.parse(rawValue, RedfishAuthMode.parse(defaultAuthMode, RedfishAuthMode.SESSION_PREFERRED));
    }

    private static String extractUri(Map<String, Object> document, String field) {
        if (document == null) {
            return null;
        }
        Object rawNode = document.get(field);
        if (!(rawNode instanceof Map<?, ?> linkNode)) {
            return null;
        }
        Object uri = linkNode.get("@odata.id");
        return uri instanceof String value && !value.isBlank() ? value : null;
    }

    private static String extractString(Map<String, Object> document, String field) {
        if (document == null) {
            return null;
        }
        Object value = document.get(field);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
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
    interface ProvisionTransport {
        ManagedAccountProvisionResult provision(ProvisionRequest request) throws Exception;
    }

    public record ManagedAccountProvisionResult(
            boolean enabled,
            boolean success,
            String endpoint,
            String username,
            String roleId,
            String message,
            String authMode,
            String authFailureCode) {

        static ManagedAccountProvisionResult notEnabled(String message) {
            return new ManagedAccountProvisionResult(false, false, null, null, null, message, null, null);
        }

        static ManagedAccountProvisionResult success(
                boolean enabled,
                String endpoint,
                String username,
                String roleId,
                String message,
                String authMode) {
            return new ManagedAccountProvisionResult(enabled, true, endpoint, username, roleId, message, authMode, null);
        }

        static ManagedAccountProvisionResult failure(
                boolean enabled,
                String endpoint,
                String username,
                String roleId,
                String message,
                String authMode,
                String authFailureCode) {
            return new ManagedAccountProvisionResult(enabled, false, endpoint, username, roleId, message, authMode, authFailureCode);
        }
    }

    record ProvisionRequest(
            String endpoint,
            String bootstrapUsername,
            String bootstrapPassword,
            String managedUsername,
            String managedPassword,
            String roleId,
            boolean insecure,
            int connectTimeoutMs,
            int readTimeoutMs,
            RedfishAuthMode authMode) {
    }
}
