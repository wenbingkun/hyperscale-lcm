package com.sc.lcm.core.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;

@ApplicationScoped
@Slf4j
public class RestApiRateLimitFilter {

    private static final String API_PREFIX = "api/";
    private static final String AUTH_PREFIX = "api/auth";

    @Inject
    SecurityIdentity identity;

    @Inject
    RestApiRateLimiter rateLimiter;

    @ServerRequestFilter(priority = Priorities.AUTHORIZATION + 10)
    public Optional<Response> filter(ContainerRequestContext requestContext, UriInfo uriInfo) {
        String path = normalizePath(uriInfo.getPath());
        if (!path.startsWith(API_PREFIX) || path.startsWith(AUTH_PREFIX)) {
            return Optional.empty();
        }

        String role;
        String principalName;
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            role = "USER";
            principalName = "anonymous";
        } else {
            role = resolveRole(identity);
            principalName = identity.getPrincipal().getName();
        }

        try {
            rateLimiter.enforce(principalName, role);
            return Optional.empty();
        } catch (RestApiRateLimiter.RateLimitExceededException exception) {
            log.warn("Rate limit exceeded for principal={} role={} path={}", principalName, role, path);
            return Optional.of(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()))
                    .entity(new ErrorResponse("API rate limit exceeded for role " + role))
                    .build());
        }
    }

    static String resolveRole(SecurityIdentity identity) {
        if (identity.hasRole("ADMIN")) {
            return "ADMIN";
        }
        if (identity.hasRole("OPERATOR")) {
            return "OPERATOR";
        }
        return "USER";
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    record ErrorResponse(String error) {
    }
}
