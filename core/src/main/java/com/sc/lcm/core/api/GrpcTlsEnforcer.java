package com.sc.lcm.core.api;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * gRPC shares the Vert.x HTTP server with REST. When the plaintext listener is kept for REST/health/metrics,
 * this route rejects gRPC calls that did not arrive over TLS, before any gRPC handler runs. TLS connections
 * on the SSL port already require a trusted client certificate (quarkus.http.ssl.client-auth=required).
 */
@ApplicationScoped
@Slf4j
public class GrpcTlsEnforcer {

    private static final String GRPC_CONTENT_TYPE = "application/grpc";
    private static final String GRPC_STATUS_PERMISSION_DENIED = "7";

    @ConfigProperty(name = "lcm.grpc.require-tls", defaultValue = "false")
    boolean requireTls;

    void register(@Observes Router router) {
        if (!requireTls) {
            return;
        }
        router.route().order(-1000).handler(ctx -> {
            String contentType = ctx.request().getHeader("content-type");
            if (contentType != null && contentType.regionMatches(true, 0, GRPC_CONTENT_TYPE, 0, GRPC_CONTENT_TYPE.length())
                    && !ctx.request().isSSL()) {
                log.warn("Rejected plaintext gRPC request from {}", ctx.request().remoteAddress());
                ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", GRPC_CONTENT_TYPE)
                        .putHeader("grpc-status", GRPC_STATUS_PERMISSION_DENIED)
                        .putHeader("grpc-message", "gRPC requires TLS with a client certificate")
                        .end();
                return;
            }
            ctx.next();
        });
    }
}
