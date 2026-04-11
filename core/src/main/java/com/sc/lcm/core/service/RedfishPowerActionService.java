package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.RedfishAuthMode;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Phase 7 受控 BMC 电源动作。
 * <p>
 * 仅支持安全子集：On / ForceOff / GracefulShutdown / GracefulRestart / ForceRestart。
 * 必须携带 {@code Idempotency-Key} header；多 ComputerSystem 时必须显式 systemId。
 */
@ApplicationScoped
@Slf4j
public class RedfishPowerActionService {

    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "On", "ForceOff", "GracefulShutdown", "GracefulRestart", "ForceRestart");

    @Inject
    SecretRefResolver secretRefResolver;

    @Inject
    RedfishTransport redfishTransport;

    @ConfigProperty(name = "lcm.claim.redfish.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs = 5000;

    @ConfigProperty(name = "lcm.claim.redfish.read-timeout-ms", defaultValue = "10000")
    int readTimeoutMs = 10000;

    @ConfigProperty(name = "lcm.claim.redfish.insecure", defaultValue = "true")
    boolean insecure = true;

    @ConfigProperty(name = "lcm.claim.redfish.auth-mode-default", defaultValue = "SESSION_PREFERRED")
    String defaultAuthMode = RedfishAuthMode.SESSION_PREFERRED.name();

    @ConfigProperty(name = "lcm.claim.redfish.power-action.idempotency-ttl-seconds", defaultValue = "300")
    long idempotencyTtlSeconds = 300;

    private final Map<String, CachedResult> idempotencyCache = new ConcurrentHashMap<>();

    Clock clock = Clock.systemDefaultZone();

    public Uni<PowerActionOutcome> execute(
            String deviceId,
            String actor,
            String idempotencyKey,
            PowerActionRequest request,
            boolean dryRun) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(PowerActionOutcome.badRequest("MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required for power-actions."));
        }
        if (request == null || request.action() == null || request.action().isBlank()) {
            return Uni.createFrom().item(PowerActionOutcome.badRequest("MISSING_ACTION",
                    "Request body must include an 'action' field."));
        }
        String action = normalizeAction(request.action());
        if (!ALLOWED_ACTIONS.contains(action)) {
            return Uni.createFrom().item(PowerActionOutcome.badRequest("UNSUPPORTED_ACTION",
                    "Power action '" + request.action() + "' is not allowed in Phase 7. "
                            + "Allowed: " + ALLOWED_ACTIONS));
        }

        String cacheKey = idempotencyCacheKey(deviceId, idempotencyKey, action, request.systemId(), dryRun);
        CachedResult cached = lookupCachedResult(cacheKey);
        if (cached != null) {
            return Uni.createFrom().item(cached.outcome().withReplayed(true));
        }

        return Panache.withSession(() ->
                DiscoveredDevice.<DiscoveredDevice>findById(deviceId)
                        .onItem().transformToUni(device -> {
                            if (device == null) {
                                return Uni.createFrom().item(PowerActionOutcome.notFound(deviceId));
                            }
                            if (device.getCredentialProfileId() == null || device.getCredentialProfileId().isBlank()) {
                                return Uni.createFrom().item(PowerActionOutcome.badRequest("NO_PROFILE",
                                        "Device has no matched credential profile."));
                            }

                            return CredentialProfile.<CredentialProfile>findById(device.getCredentialProfileId())
                                    .onItem().transformToUni(profile -> {
                                        if (profile == null) {
                                            return Uni.createFrom().item(PowerActionOutcome.badRequest("NO_PROFILE",
                                                    "Credential profile not found."));
                                        }
                                        return executeOnTransport(device, profile, action, request.systemId(), dryRun, actor);
                                    });
                        }))
                .onItem().invoke(outcome -> {
                    if (outcome != null && shouldCacheOutcome(outcome)) {
                        idempotencyCache.put(cacheKey,
                                new CachedResult(outcome, Instant.now(clock).plusSeconds(idempotencyTtlSeconds)));
                    }
                });
    }

    private Uni<PowerActionOutcome> executeOnTransport(
            DiscoveredDevice device,
            CredentialProfile profile,
            String action,
            String requestedSystemId,
            boolean dryRun,
            String actor) {
        String endpoint = RedfishClaimExecutor.resolveEndpoint(device);
        if (endpoint == null) {
            return Uni.createFrom().item(PowerActionOutcome.badRequest("NO_ENDPOINT",
                    "BMC endpoint is missing on the discovered device."));
        }

        RedfishAuthMode authMode = RedfishAuthMode.parse(
                device.getRedfishAuthModeOverride() != null
                        ? device.getRedfishAuthModeOverride()
                        : profile.getRedfishAuthMode(),
                RedfishAuthMode.parse(defaultAuthMode, RedfishAuthMode.SESSION_PREFERRED));

        return secretRefResolver.resolve(profile)
                .onItem().transformToUni(credentials -> {
                    if (!credentials.isReady()) {
                        return Uni.createFrom().item(PowerActionOutcome.badRequest("CREDENTIALS_NOT_READY",
                                "Power action blocked because secret refs are not ready. " + credentials.getMessage()));
                    }

                    RedfishTransport.RequestOptions options = new RedfishTransport.RequestOptions(
                            endpoint,
                            credentials.getUsername().getValue(),
                            credentials.getPassword().getValue(),
                            insecure,
                            connectTimeoutMs,
                            readTimeoutMs,
                            authMode);

                    return Uni.createFrom().item(() -> {
                                try {
                                    return performBlocking(options, action, requestedSystemId, dryRun, device.getId(), actor);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                            .onFailure().recoverWithItem(error -> mapTransportFailure(error, action));
                });
    }

    PowerActionOutcome performBlocking(
            RedfishTransport.RequestOptions options,
            String action,
            String requestedSystemId,
            boolean dryRun,
            String deviceId,
            String actor) throws Exception {
        RedfishTransport.AuthContext authContext = redfishTransport.open(options);
        Map<String, Object> systemsCollection = redfishTransport.getJson(authContext, RedfishTransport.SYSTEMS_PATH, true);
        List<String> memberUris = memberUris(systemsCollection);

        if (memberUris.isEmpty()) {
            return PowerActionOutcome.failure("NO_SYSTEMS",
                    "BMC reported no ComputerSystem members; power action cannot be targeted.",
                    authContext.actualAuthMode());
        }

        String resolvedSystemUri;
        String resolvedSystemId;
        if (requestedSystemId != null && !requestedSystemId.isBlank()) {
            resolvedSystemUri = matchSystemUri(memberUris, requestedSystemId);
            if (resolvedSystemUri == null) {
                return PowerActionOutcome.badRequest("SYSTEM_NOT_FOUND",
                        "ComputerSystem '" + requestedSystemId + "' not found on this BMC.");
            }
            resolvedSystemId = requestedSystemId;
        } else if (memberUris.size() == 1) {
            resolvedSystemUri = memberUris.getFirst();
            resolvedSystemId = trailingId(resolvedSystemUri);
        } else {
            return PowerActionOutcome.badRequest("MULTIPLE_SYSTEMS_REQUIRE_SYSTEM_ID",
                    "BMC exposes " + memberUris.size() + " ComputerSystem members; "
                            + "request must specify 'systemId'. Available: " + memberUris);
        }

        Map<String, Object> systemDocument = redfishTransport.getJson(authContext, resolvedSystemUri, true);
        ResetActionInfo resetActionInfo = resolveResetAction(systemDocument);
        if (resetActionInfo == null) {
            return PowerActionOutcome.failure("CAPABILITY_MISSING",
                    "ComputerSystem " + resolvedSystemId + " does not expose a Reset action.",
                    authContext.actualAuthMode());
        }
        if (!resetActionInfo.allowedValues().isEmpty() && !resetActionInfo.allowedValues().contains(action)) {
            return PowerActionOutcome.badRequest("ACTION_NOT_SUPPORTED_BY_BMC",
                    "BMC reports action '" + action + "' is not in allowable values "
                            + resetActionInfo.allowedValues());
        }

        if (dryRun) {
            return PowerActionOutcome.dryRun(
                    action,
                    resolvedSystemId,
                    resetActionInfo.target(),
                    authContext.actualAuthMode(),
                    resetActionInfo.allowedValues());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ResetType", action);
        RedfishTransport.WriteResult writeResult = redfishTransport.writeJson(
                authContext, "POST", resetActionInfo.target(), payload, false);

        log.info("⚡ BMC power action {} on device {} system {} returned HTTP {} (actor={})",
                action, deviceId, resolvedSystemId, writeResult.statusCode(), actor);

        if (writeResult.statusCode() == 202) {
            return PowerActionOutcome.accepted(
                    action,
                    resolvedSystemId,
                    resetActionInfo.target(),
                    authContext.actualAuthMode(),
                    writeResult.location());
        }
        return PowerActionOutcome.completed(
                action,
                resolvedSystemId,
                resetActionInfo.target(),
                authContext.actualAuthMode());
    }

    private static PowerActionOutcome mapTransportFailure(Throwable error, String action) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null && !(cause instanceof RedfishTransport.RedfishTransportException)) {
            cause = cause.getCause();
        }
        if (cause instanceof RedfishTransport.RedfishTransportException transportException) {
            return PowerActionOutcome.failure(
                    transportException.failureCode(),
                    "BMC " + action + " failed: " + transportException.getMessage(),
                    null);
        }
        return PowerActionOutcome.failure(
                "UNKNOWN_ERROR",
                "BMC " + action + " failed: " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                null);
    }

    private CachedResult lookupCachedResult(String cacheKey) {
        CachedResult cached = idempotencyCache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt().isBefore(Instant.now(clock))) {
            idempotencyCache.remove(cacheKey, cached);
            return null;
        }
        return cached;
    }

    private static boolean shouldCacheOutcome(PowerActionOutcome outcome) {
        return outcome.status() == Status.COMPLETED
                || outcome.status() == Status.ACCEPTED
                || outcome.status() == Status.DRY_RUN;
    }

    private static String idempotencyCacheKey(String deviceId, String idempotencyKey, String action,
            String systemId, boolean dryRun) {
        return deviceId + "::" + idempotencyKey + "::" + action + "::"
                + (systemId == null ? "_" : systemId) + "::" + dryRun;
    }

    private static String normalizeAction(String raw) {
        String trimmed = raw.trim();
        for (String allowed : ALLOWED_ACTIONS) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return allowed;
            }
        }
        return trimmed;
    }

    private static List<String> memberUris(Map<String, Object> document) {
        Object rawMembers = document.get("Members");
        if (!(rawMembers instanceof List<?> members)) {
            return List.of();
        }
        List<String> uris = new ArrayList<>();
        for (Object member : members) {
            if (member instanceof Map<?, ?> memberMap) {
                Object rawUri = memberMap.get("@odata.id");
                if (rawUri instanceof String uriValue && !uriValue.isBlank()) {
                    uris.add(uriValue);
                }
            }
        }
        return uris;
    }

    private static String matchSystemUri(List<String> uris, String requestedId) {
        String needle = requestedId.toLowerCase(Locale.ROOT);
        for (String uri : uris) {
            if (trailingId(uri).equalsIgnoreCase(needle)) {
                return uri;
            }
            if (uri.equalsIgnoreCase(requestedId)) {
                return uri;
            }
        }
        return null;
    }

    private static String trailingId(String uri) {
        if (uri == null) {
            return "";
        }
        String trimmed = uri.replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private static ResetActionInfo resolveResetAction(Map<String, Object> systemDocument) {
        Object rawActions = systemDocument.get("Actions");
        if (!(rawActions instanceof Map<?, ?> actions)) {
            return null;
        }
        Object rawReset = actions.get("#ComputerSystem.Reset");
        if (!(rawReset instanceof Map<?, ?> reset)) {
            return null;
        }
        Object rawTarget = reset.get("target");
        if (!(rawTarget instanceof String target) || target.isBlank()) {
            return null;
        }
        Object rawAllowed = reset.get("ResetType@Redfish.AllowableValues");
        List<String> allowed = new ArrayList<>();
        if (rawAllowed instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof String stringValue && !stringValue.isBlank()) {
                    allowed.add(stringValue);
                }
            }
        }
        return new ResetActionInfo(target, allowed);
    }

    public enum Status {
        COMPLETED, ACCEPTED, DRY_RUN, BAD_REQUEST, NOT_FOUND, FAILURE
    }

    public record PowerActionRequest(String action, String systemId) {
    }

    public record PowerActionOutcome(
            Status status,
            String action,
            String systemId,
            String targetUri,
            String authMode,
            String taskLocation,
            List<String> allowedValues,
            String failureCode,
            String message,
            boolean replayed) {

        static PowerActionOutcome completed(String action, String systemId, String targetUri, String authMode) {
            return new PowerActionOutcome(Status.COMPLETED, action, systemId, targetUri, authMode,
                    null, List.of(), null, "Power action completed.", false);
        }

        static PowerActionOutcome accepted(String action, String systemId, String targetUri, String authMode,
                String taskLocation) {
            return new PowerActionOutcome(Status.ACCEPTED, action, systemId, targetUri, authMode,
                    taskLocation, List.of(), null, "Power action accepted.", false);
        }

        static PowerActionOutcome dryRun(String action, String systemId, String targetUri, String authMode,
                List<String> allowedValues) {
            return new PowerActionOutcome(Status.DRY_RUN, action, systemId, targetUri, authMode,
                    null, allowedValues == null ? List.of() : allowedValues,
                    null, "Dry run; no BMC mutation performed.", false);
        }

        static PowerActionOutcome badRequest(String code, String message) {
            return new PowerActionOutcome(Status.BAD_REQUEST, null, null, null, null, null, List.of(),
                    code, message, false);
        }

        static PowerActionOutcome notFound(String deviceId) {
            return new PowerActionOutcome(Status.NOT_FOUND, null, null, null, null, null, List.of(),
                    "DEVICE_NOT_FOUND", "Device not found: " + deviceId, false);
        }

        static PowerActionOutcome failure(String failureCode, String message, String authMode) {
            return new PowerActionOutcome(Status.FAILURE, null, null, null, authMode, null, List.of(),
                    failureCode, message, false);
        }

        PowerActionOutcome withReplayed(boolean replayed) {
            return new PowerActionOutcome(status, action, systemId, targetUri, authMode, taskLocation,
                    allowedValues, failureCode, message, replayed);
        }
    }

    private record ResetActionInfo(String target, List<String> allowedValues) {
    }

    private record CachedResult(PowerActionOutcome outcome, Instant expiresAt) {
    }

    /** Exposed only for test fixtures that need to flush the idempotency cache between cases. */
    void resetIdempotencyCache() {
        idempotencyCache.clear();
    }
}
