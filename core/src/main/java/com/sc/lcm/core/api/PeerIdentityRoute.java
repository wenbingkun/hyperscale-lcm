package com.sc.lcm.core.api;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HexFormat;
import javax.net.ssl.SSLSession;

/**
 * Derives the Satellite identity from the client certificate verified in the TLS handshake and exposes it to the
 * gRPC layer as {@value #FINGERPRINT_HEADER}. Any value sent by the client is removed first, so the header can only
 * originate from the handshake.
 */
@ApplicationScoped
@Slf4j
public class PeerIdentityRoute {

    public static final String FINGERPRINT_HEADER = "x-lcm-peer-fingerprint";

    void register(@Observes Router router) {
        router.route().order(-2000).handler(ctx -> {
            ctx.request().headers().remove(FINGERPRINT_HEADER);
            if (ctx.request().isSSL()) {
                String fingerprint = fingerprint(ctx.request().sslSession());
                if (fingerprint != null) {
                    ctx.request().headers().set(FINGERPRINT_HEADER, fingerprint);
                }
            }
            ctx.next();
        });
    }

    static String fingerprint(SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0) {
                return null;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
        } catch (Exception e) {
            log.debug("No verified peer certificate on this connection: {}", e.getMessage());
            return null;
        }
    }
}
