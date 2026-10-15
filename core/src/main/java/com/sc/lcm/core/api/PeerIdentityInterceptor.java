package com.sc.lcm.core.api;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

/** Makes the handshake-derived client certificate fingerprint available to gRPC handlers. */
@ApplicationScoped
@GlobalInterceptor
public class PeerIdentityInterceptor implements ServerInterceptor {

    public static final Context.Key<String> PEER_FINGERPRINT = Context.key("lcm-peer-fingerprint");

    private static final Metadata.Key<String> HEADER =
            Metadata.Key.of(PeerIdentityRoute.FINGERPRINT_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String fingerprint = headers.get(HEADER);
        return Contexts.interceptCall(Context.current().withValue(PEER_FINGERPRINT, fingerprint), call, headers, next);
    }
}
