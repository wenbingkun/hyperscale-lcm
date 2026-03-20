package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.CredentialProfile;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 解析和校验凭据档案中的 secret ref。
 * 当前支持:
 * - env://ENV_VAR
 * - literal://value (默认仅 dev/test 建议开启)
 * - vault://mount/path#field
 */
@ApplicationScoped
public class SecretRefResolver {

    @Inject
    SecretManagerClient secretManagerClient;

    @ConfigProperty(name = "lcm.claim.secret-resolver.allow-env", defaultValue = "true")
    boolean allowEnvRefs = true;

    @ConfigProperty(name = "lcm.claim.secret-resolver.allow-literal", defaultValue = "false")
    boolean allowLiteralRefs = false;

    @ConfigProperty(name = "lcm.claim.secret-resolver.allowed-env-prefixes", defaultValue = "LCM_BMC_,BMC_")
    String allowedEnvPrefixesRaw = "LCM_BMC_,BMC_";

    Function<String, String> envReader = System::getenv;

    public Uni<ResolvedCredentialMaterial> resolve(CredentialProfile profile) {
        String profileName = profile != null ? profile.getName() : "unknown";
        return resolvePair(
                profile != null ? profile.getUsernameSecretRef() : null,
                profile != null ? profile.getPasswordSecretRef() : null,
                "username",
                "password",
                "Matched credential profile '" + profileName + "'. Secret refs are resolvable and ready for automated claim.",
                "Matched credential profile '" + profileName + "', but secret refs are not ready: ");
    }

    public Uni<ResolvedCredentialMaterial> resolveManagedAccount(CredentialProfile profile) {
        if (profile == null || !profile.isManagedAccountEnabled()) {
            return Uni.createFrom().item(ResolvedCredentialMaterial.disabled(
                    "Managed account provisioning is disabled for this credential profile."));
        }

        String profileName = profile.getName() != null ? profile.getName() : "unknown";
        return resolvePair(
                profile.getManagedUsernameSecretRef(),
                profile.getManagedPasswordSecretRef(),
                "managed username",
                "managed password",
                "Managed account secret refs for credential profile '" + profileName + "' are resolvable and ready for provisioning.",
                "Managed account secret refs for credential profile '" + profileName + "' are not ready: ");
    }

    Uni<ResolvedSecret> resolveSecret(String ref, String fieldName) {
        if (ref == null || ref.isBlank()) {
            return Uni.createFrom().item(ResolvedSecret.unresolved(null, fieldName + " secret ref is not configured."));
        }

        int schemeEnd = ref.indexOf("://");
        if (schemeEnd <= 0) {
            return Uni.createFrom().item(ResolvedSecret.unresolved(null, fieldName + " secret ref must use a supported scheme."));
        }

        String scheme = ref.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String value = ref.substring(schemeEnd + 3);

        return switch (scheme) {
            case "env" -> Uni.createFrom().item(resolveEnvSecret(value, fieldName));
            case "literal" -> Uni.createFrom().item(resolveLiteralSecret(value, fieldName));
            case "vault" -> Uni.createFrom().item(() -> resolveVaultSecret(ref, fieldName))
                    .runSubscriptionOn(Infrastructure.getDefaultExecutor());
            default -> Uni.createFrom().item(ResolvedSecret.unresolved(
                    scheme.toUpperCase(Locale.ROOT),
                    fieldName + " secret ref scheme '" + scheme + "' is not supported."));
        };
    }

    private ResolvedSecret resolveEnvSecret(String envVarName, String fieldName) {
        if (!allowEnvRefs) {
            return ResolvedSecret.unresolved("ENV", fieldName + " env secret refs are disabled by configuration.");
        }
        if (envVarName == null || envVarName.isBlank()) {
            return ResolvedSecret.unresolved("ENV", fieldName + " env secret ref is empty.");
        }
        if (!isAllowedEnvVar(envVarName)) {
            return ResolvedSecret.unresolved("ENV",
                    fieldName + " env var '" + envVarName + "' does not match the allowed prefixes: "
                            + allowedPrefixesDescription());
        }

        String secret = envReader.apply(envVarName);
        if (secret == null || secret.isBlank()) {
            return ResolvedSecret.unresolved("ENV",
                    fieldName + " env var '" + envVarName + "' is not set.");
        }

        return ResolvedSecret.resolved("ENV", secret);
    }

    private ResolvedSecret resolveLiteralSecret(String value, String fieldName) {
        if (!allowLiteralRefs) {
            return ResolvedSecret.unresolved("LITERAL",
                    fieldName + " literal secret refs are disabled by configuration.");
        }
        if (value == null || value.isBlank()) {
            return ResolvedSecret.unresolved("LITERAL", fieldName + " literal secret ref is empty.");
        }
        return ResolvedSecret.resolved("LITERAL", value);
    }

    private ResolvedSecret resolveVaultSecret(String ref, String fieldName) {
        if (secretManagerClient == null) {
            return ResolvedSecret.unresolved("VAULT", fieldName + " Secret manager client is not available.");
        }
        SecretManagerClient.SecretResolution resolution = secretManagerClient.resolve(ref, fieldName);
        if (resolution.resolved()) {
            return ResolvedSecret.resolved("VAULT", resolution.value());
        }
        return ResolvedSecret.unresolved("VAULT", resolution.message());
    }

    private boolean isAllowedEnvVar(String envVarName) {
        return allowedEnvPrefixes().stream().anyMatch(envVarName::startsWith);
    }

    private java.util.List<String> allowedEnvPrefixes() {
        return Arrays.stream(allowedEnvPrefixesRaw.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
    }

    private String allowedPrefixesDescription() {
        return allowedEnvPrefixes().stream().collect(Collectors.joining(", "));
    }

    private static String mergeSources(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank() || left.equals(right)) {
            return left;
        }
        return left + "+" + right;
    }

    private Uni<ResolvedCredentialMaterial> resolvePair(
            String usernameRef,
            String passwordRef,
            String usernameFieldName,
            String passwordFieldName,
            String readyMessage,
            String notReadyPrefix) {
        return resolveSecret(usernameRef, usernameFieldName)
                .onItem().transformToUni(username -> resolveSecret(passwordRef, passwordFieldName)
                        .map(password -> buildMaterial(username, password, readyMessage, notReadyPrefix)));
    }

    private static ResolvedCredentialMaterial buildMaterial(
            ResolvedSecret username,
            ResolvedSecret password,
            String readyMessage,
            String notReadyPrefix) {
        boolean ready = username.isResolved() && password.isResolved();
        String source = mergeSources(username.getSource(), password.getSource());
        String message;
        if (ready) {
            message = readyMessage;
        } else {
            message = notReadyPrefix + username.getMessage() + " " + password.getMessage();
        }

        return new ResolvedCredentialMaterial(
                ready,
                source,
                message,
                username,
                password);
    }

    @Getter
    public static final class ResolvedCredentialMaterial {
        private final boolean ready;
        private final String credentialSource;
        private final String message;
        private final ResolvedSecret username;
        private final ResolvedSecret password;

        private ResolvedCredentialMaterial(
                boolean ready,
                String credentialSource,
                String message,
                ResolvedSecret username,
                ResolvedSecret password) {
            this.ready = ready;
            this.credentialSource = credentialSource;
            this.message = message;
            this.username = username;
            this.password = password;
        }

        static ResolvedCredentialMaterial disabled(String message) {
            return new ResolvedCredentialMaterial(
                    false,
                    null,
                    message,
                    ResolvedSecret.unresolved(null, message),
                    ResolvedSecret.unresolved(null, message));
        }
    }

    @Getter
    public static final class ResolvedSecret {
        private final boolean resolved;
        private final String source;
        private final String value;
        private final String message;

        private ResolvedSecret(boolean resolved, String source, String value, String message) {
            this.resolved = resolved;
            this.source = source;
            this.value = value;
            this.message = message;
        }

        static ResolvedSecret resolved(String source, String value) {
            return new ResolvedSecret(true, source, value, source + " secret ref resolved.");
        }

        static ResolvedSecret unresolved(String source, String message) {
            return new ResolvedSecret(false, source, null, message);
        }
    }
}
