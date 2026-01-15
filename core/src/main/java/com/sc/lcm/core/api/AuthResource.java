package com.sc.lcm.core.api;

import io.quarkus.security.Authenticated;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;

/**
 * 认证 API (P5-1)
 * 
 * 生成 JWT Token（仅用于开发/测试）
 * 生产环境应使用外部 IdP (Keycloak, Auth0 等)
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
@Slf4j
public class AuthResource {

    @Inject
    JsonWebToken jwt;

    /**
     * 登录并获取 JWT Token (开发用)
     */
    @POST
    @Path("/login")
    public Uni<Response> login(LoginRequest request) {
        // 简化的用户验证（生产环境应查数据库/LDAP）
        String role = validateUser(request.username(), request.password());

        if (role == null) {
            log.warn("❌ Authentication failed for user: {}", request.username());
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid credentials"))
                    .build());
        }

        // 生成 JWT Token
        String token = Jwt.issuer("https://lcm.example.com")
                .upn(request.username())
                .groups(Set.of(role))
                .claim("tenant_id", request.tenantId() != null ? request.tenantId() : "default")
                .expiresIn(Duration.ofHours(8))
                .sign();

        log.info("✅ User {} authenticated with role {}", request.username(), role);

        return Uni.createFrom().item(Response.ok(new TokenResponse(token, role, 28800))
                .build());
    }

    /**
     * 刷新 Token
     */
    @POST
    @Path("/refresh")
    @Authenticated
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> refresh() {
        if (jwt == null || jwt.getName() == null) {
            log.warn("❌ Token refresh failed: No valid principal found in token");
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid or missing token"))
                    .build());
        }

        String username = jwt.getName();
        Set<String> roles = jwt.getGroups();
        String tenantId = jwt.getClaim("tenant_id");

        log.info("🔄 Refreshing token for user: {}, roles: {}, tenant: {}", username, roles, tenantId);

        // 生成新 JWT Token
        String newToken = Jwt.issuer("https://lcm.example.com")
                .upn(username)
                .groups(roles)
                .claim("tenant_id", tenantId != null ? tenantId : "default")
                .expiresIn(Duration.ofHours(8))
                .sign();

        String primaryRole = roles != null && !roles.isEmpty() ? roles.iterator().next() : "USER";

        return Uni.createFrom().item(Response.ok(new TokenResponse(newToken, primaryRole, 28800))
                .build());
    }

    /**
     * 简化的用户验证（开发用）
     * 生产环境应使用数据库/LDAP/外部 IdP
     */
    private String validateUser(String username, String password) {
        // 硬编码用户（仅开发测试）
        if ("admin".equals(username) && "admin123".equals(password)) {
            return "ADMIN";
        }
        if ("operator".equals(username) && "operator123".equals(password)) {
            return "OPERATOR";
        }
        if ("user".equals(username) && "user123".equals(password)) {
            return "USER";
        }
        return null;
    }

    // ============== DTO Records ==============

    public record LoginRequest(String username, String password, String tenantId) {
    }

    public record TokenResponse(String token, String role, long expiresIn) {
    }

    public record ErrorResponse(String error) {
    }
}
