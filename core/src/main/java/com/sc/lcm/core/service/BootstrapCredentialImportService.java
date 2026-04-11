package com.sc.lcm.core.service;

import com.sc.lcm.core.api.CredentialProfileResource.CredentialProfileRequest;
import com.sc.lcm.core.domain.CredentialProfile;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 将 CMDB / 交付台账导出的 bootstrap 凭据记录导入为 CredentialProfile。
 * 这样可以直接复用现有的 claim 规划链路，而不引入第二套匹配状态机。
 */
@ApplicationScoped
@Slf4j
public class BootstrapCredentialImportService {

    public Uni<ImportResult> importEntries(List<CredentialProfileRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().item(new ImportResult(0, 0, 0, List.of()));
        }

        return Panache.withTransaction(() ->
                Multi.createFrom().iterable(entries)
                        .onItem().transformToUniAndConcatenate(this::upsertEntry)
                        .collect().asList()
                        .map(this::summarize));
    }

    private Uni<EntryResult> upsertEntry(CredentialProfileRequest entry) {
        if (entry == null) {
            return Uni.createFrom().item(EntryResult.skipped("<null>", "Import entry is null."));
        }

        String sourceType = normalizeSourceType(entry.sourceType());
        String name = resolveName(entry, sourceType);
        if (name == null || name.isBlank()) {
            return Uni.createFrom().item(EntryResult.skipped("<unnamed>",
                    "Import entry must include name, externalRef, or a concrete IP/hostname matching key."));
        }

        Uni<CredentialProfile> existingUni = hasText(entry.externalRef())
                ? CredentialProfile.findBySourceAndExternalRef(sourceType, entry.externalRef())
                : CredentialProfile.findByNameExact(name);

        return existingUni.onItem().transformToUni(existing -> {
            boolean created = existing == null;
            CredentialProfile profile = created ? new CredentialProfile() : existing;
            applyImportedProfile(profile, entry, sourceType, name);

            Uni<?> persistUni = created ? profile.persist() : Uni.createFrom().item(profile);
            return persistUni.replaceWith(created
                    ? EntryResult.created(name)
                    : EntryResult.updated(name));
        });
    }

    static void applyImportedProfile(
            CredentialProfile profile,
            CredentialProfileRequest request,
            String sourceType,
            String resolvedName) {
        profile.setName(resolvedName);
        profile.setProtocol(hasText(request.protocol()) ? request.protocol() : "REDFISH");
        profile.setEnabled(request.enabled() == null || request.enabled());
        profile.setAutoClaim(request.autoClaim() == null || request.autoClaim());
        profile.setPriority(request.priority() == null ? 1000 : request.priority());
        profile.setSourceType(sourceType);
        profile.setExternalRef(request.externalRef());
        profile.setVendorPattern(request.vendorPattern());
        profile.setModelPattern(request.modelPattern());
        profile.setSubnetCidr(request.subnetCidr());
        profile.setDeviceType(hasText(request.deviceType()) ? request.deviceType() : "BMC_ENABLED");
        profile.setHostnamePattern(request.hostnamePattern());
        profile.setIpAddressPattern(request.ipAddressPattern());
        profile.setMacAddressPattern(request.macAddressPattern());
        profile.setRedfishTemplate(request.redfishTemplate());
        profile.setRedfishAuthMode(request.redfishAuthMode());
        profile.setUsernameSecretRef(request.usernameSecretRef());
        profile.setPasswordSecretRef(request.passwordSecretRef());
        profile.setManagedAccountEnabled(request.managedAccountEnabled() != null && request.managedAccountEnabled());
        profile.setManagedUsernameSecretRef(request.managedUsernameSecretRef());
        profile.setManagedPasswordSecretRef(request.managedPasswordSecretRef());
        profile.setManagedAccountRoleId(hasText(request.managedAccountRoleId())
                ? request.managedAccountRoleId()
                : profile.getManagedAccountRoleId());
        profile.setDescription(hasText(request.description())
                ? request.description()
                : defaultDescription(sourceType, request.externalRef()));
    }

    private ImportResult summarize(List<EntryResult> entryResults) {
        int created = (int) entryResults.stream().filter(result -> result.status() == EntryStatus.CREATED).count();
        int updated = (int) entryResults.stream().filter(result -> result.status() == EntryStatus.UPDATED).count();
        int skipped = (int) entryResults.stream().filter(result -> result.status() == EntryStatus.SKIPPED).count();
        return new ImportResult(created, updated, skipped, entryResults);
    }

    private static String resolveName(CredentialProfileRequest entry, String sourceType) {
        if (hasText(entry.name())) {
            return entry.name().trim();
        }
        if (hasText(entry.externalRef())) {
            return sourceType.toLowerCase() + "-" + entry.externalRef().trim();
        }
        if (hasText(entry.ipAddressPattern())) {
            return sourceType.toLowerCase() + "-ip-" + entry.ipAddressPattern().trim();
        }
        if (hasText(entry.hostnamePattern())) {
            return sourceType.toLowerCase() + "-host-" + entry.hostnamePattern().trim();
        }
        return null;
    }

    private static String defaultDescription(String sourceType, String externalRef) {
        if (hasText(externalRef)) {
            return "Imported from " + sourceType + " record " + externalRef + ".";
        }
        return "Imported from " + sourceType + ".";
    }

    private static String normalizeSourceType(String rawSourceType) {
        if (!hasText(rawSourceType)) {
            return "CMDB";
        }
        return rawSourceType.trim().toUpperCase();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ImportResult(
            int created,
            int updated,
            int skipped,
            List<EntryResult> results) {
    }

    public record EntryResult(
            String name,
            EntryStatus status,
            String message) {

        static EntryResult created(String name) {
            return new EntryResult(name, EntryStatus.CREATED, "Created credential profile.");
        }

        static EntryResult updated(String name) {
            return new EntryResult(name, EntryStatus.UPDATED, "Updated credential profile.");
        }

        static EntryResult skipped(String name, String message) {
            return new EntryResult(name, EntryStatus.SKIPPED, message);
        }
    }

    public enum EntryStatus {
        CREATED,
        UPDATED,
        SKIPPED
    }
}
