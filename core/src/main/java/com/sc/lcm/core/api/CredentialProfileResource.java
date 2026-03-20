package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.CredentialProfile;
import com.sc.lcm.core.service.BootstrapCredentialImportService;
import com.sc.lcm.core.service.CmdbBootstrapSyncService;
import com.sc.lcm.core.service.RedfishTemplateCatalog;
import com.sc.lcm.core.service.SecretRefResolver;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * 管理自动纳管所需的凭据档案与 Redfish 模板绑定。
 */
@Path("/api/credential-profiles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ADMIN")
public class CredentialProfileResource {

    @jakarta.inject.Inject
    SecretRefResolver secretRefResolver;

    @jakarta.inject.Inject
    RedfishTemplateCatalog redfishTemplateCatalog;

    @jakarta.inject.Inject
    BootstrapCredentialImportService bootstrapCredentialImportService;

    @jakarta.inject.Inject
    CmdbBootstrapSyncService cmdbBootstrapSyncService;

    @GET
    public Uni<List<CredentialProfile>> listProfiles(@QueryParam("limit") @DefaultValue("100") int limit) {
        return CredentialProfile.findAll().page(0, limit).list();
    }

    @GET
    @Path("/templates")
    public List<RedfishTemplateCatalog.TemplateMetadata> listTemplates() {
        return redfishTemplateCatalog.listTemplates();
    }

    @POST
    public Uni<Response> createProfile(CredentialProfileRequest request) {
        CredentialProfile profile = new CredentialProfile();
        apply(profile, request);

        return Panache.withTransaction(profile::persist)
                .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build());
    }

    @POST
    @Path("/import/bootstrap")
    public Uni<Response> importBootstrapProfiles(BootstrapImportRequest request) {
        return bootstrapCredentialImportService.importEntries(request != null ? request.entries() : null)
                .map(result -> Response.ok(result).build());
    }

    @POST
    @Path("/sync/cmdb")
    public Uni<Response> syncCmdbProfiles() {
        return cmdbBootstrapSyncService.syncNow()
                .map(result -> switch (result.status()) {
                    case SUCCESS -> Response.ok(result).build();
                    case SKIPPED -> Response.ok(result).build();
                    case FAILURE -> Response.status(Response.Status.BAD_GATEWAY).entity(result).build();
                });
    }

    @PUT
    @Path("/{id}")
    public Uni<Response> updateProfile(@PathParam("id") String id, CredentialProfileRequest request) {
        return Panache.withTransaction(() -> CredentialProfile.<CredentialProfile>findById(id)
                .onItem().transformToUni(profile -> {
                    if (profile == null) {
                        return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Credential profile not found"))
                                .build());
                    }

                    apply(profile, request);
                    return Uni.createFrom().item(Response.ok(profile).build());
                }));
    }

    @DELETE
    @Path("/{id}")
    public Uni<Response> deleteProfile(@PathParam("id") String id) {
        return Panache.withTransaction(() -> CredentialProfile.deleteById(id)
                .map(deleted -> deleted
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Credential profile not found"))
                                .build()));
    }

    @POST
    @Path("/{id}/validate")
    public Uni<Response> validateProfile(@PathParam("id") String id) {
        return CredentialProfile.<CredentialProfile>findById(id)
                .onItem().transformToUni(profile -> {
                    if (profile == null) {
                        return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                                .entity(new ErrorResponse("Credential profile not found"))
                                .build());
                    }

                    return Uni.combine().all().unis(
                                    secretRefResolver.resolve(profile),
                                    secretRefResolver.resolveManagedAccount(profile))
                            .asTuple()
                            .map(tuple -> {
                                SecretRefResolver.ResolvedCredentialMaterial material = tuple.getItem1();
                                SecretRefResolver.ResolvedCredentialMaterial managedMaterial = tuple.getItem2();
                                ValidationResponse response = new ValidationResponse(
                                        profile.getId(),
                                        profile.getName(),
                                        material.isReady(),
                                        material.getCredentialSource(),
                                        material.getMessage(),
                                        material.getUsername().isResolved(),
                                        material.getUsername().getMessage(),
                                        material.getPassword().isResolved(),
                                        material.getPassword().getMessage(),
                                        profile.isManagedAccountEnabled(),
                                        managedMaterial.isReady(),
                                        managedMaterial.getMessage(),
                                        managedMaterial.getUsername().isResolved(),
                                        managedMaterial.getUsername().getMessage(),
                                        managedMaterial.getPassword().isResolved(),
                                        managedMaterial.getPassword().getMessage());
                                return Response.ok(response).build();
                            });
                });
    }

    private static void apply(CredentialProfile profile, CredentialProfileRequest request) {
        profile.setName(request.name());
        profile.setProtocol(request.protocol() == null || request.protocol().isBlank() ? "REDFISH" : request.protocol());
        profile.setEnabled(request.enabled() == null ? profile.isEnabled() : request.enabled());
        profile.setAutoClaim(request.autoClaim() == null ? profile.isAutoClaim() : request.autoClaim());
        profile.setPriority(request.priority() == null ? profile.getPriority() : request.priority());
        profile.setSourceType(request.sourceType() == null || request.sourceType().isBlank()
                ? profile.getSourceType()
                : request.sourceType().trim().toUpperCase());
        profile.setExternalRef(request.externalRef());
        profile.setVendorPattern(request.vendorPattern());
        profile.setModelPattern(request.modelPattern());
        profile.setSubnetCidr(request.subnetCidr());
        profile.setDeviceType(request.deviceType());
        profile.setHostnamePattern(request.hostnamePattern());
        profile.setIpAddressPattern(request.ipAddressPattern());
        profile.setMacAddressPattern(request.macAddressPattern());
        profile.setRedfishTemplate(request.redfishTemplate());
        profile.setUsernameSecretRef(request.usernameSecretRef());
        profile.setPasswordSecretRef(request.passwordSecretRef());
        profile.setManagedAccountEnabled(request.managedAccountEnabled() == null
                ? profile.isManagedAccountEnabled()
                : request.managedAccountEnabled());
        profile.setManagedUsernameSecretRef(request.managedUsernameSecretRef());
        profile.setManagedPasswordSecretRef(request.managedPasswordSecretRef());
        profile.setManagedAccountRoleId(request.managedAccountRoleId() == null || request.managedAccountRoleId().isBlank()
                ? profile.getManagedAccountRoleId()
                : request.managedAccountRoleId());
        profile.setDescription(request.description());
    }

    public record CredentialProfileRequest(
            String name,
            String protocol,
            Boolean enabled,
            Boolean autoClaim,
            Integer priority,
            String sourceType,
            String externalRef,
            String vendorPattern,
            String modelPattern,
            String subnetCidr,
            String deviceType,
            String hostnamePattern,
            String ipAddressPattern,
            String macAddressPattern,
            String redfishTemplate,
            String usernameSecretRef,
            String passwordSecretRef,
            Boolean managedAccountEnabled,
            String managedUsernameSecretRef,
            String managedPasswordSecretRef,
            String managedAccountRoleId,
            String description) {
    }

    public record BootstrapImportRequest(List<CredentialProfileRequest> entries) {
    }

    public record ErrorResponse(String error) {
    }

    public record ValidationResponse(
            String id,
            String name,
            boolean ready,
            String credentialSource,
            String message,
            boolean usernameReady,
            String usernameMessage,
            boolean passwordReady,
            String passwordMessage,
            boolean managedAccountEnabled,
            boolean managedAccountReady,
            String managedAccountMessage,
            boolean managedUsernameReady,
            String managedUsernameMessage,
            boolean managedPasswordReady,
            String managedPasswordMessage) {
    }
}
