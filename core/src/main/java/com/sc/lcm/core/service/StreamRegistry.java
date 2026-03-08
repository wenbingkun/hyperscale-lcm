package com.sc.lcm.core.service;

import com.sc.lcm.core.grpc.StreamResponse;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StreamRegistry {

    private static final Logger LOG = Logger.getLogger(StreamRegistry.class);

    // Map Satellite ID -> Emitter
    private final Map<String, MultiEmitter<? super StreamResponse>> emitters = new ConcurrentHashMap<>();

    public void register(String satelliteId, MultiEmitter<? super StreamResponse> emitter) {
        emitters.put(satelliteId, emitter);
        LOG.infof("✅ Satellite connected to stream: %s", satelliteId);

        emitter.onTermination(() -> {
            emitters.remove(satelliteId);
            LOG.infof("❌ Satellite disconnected from stream: %s", satelliteId);
        });
    }

    public void sendCommand(String satelliteId, String commandType, String payload, Map<String, String> traceContext) {
        MultiEmitter<? super StreamResponse> emitter = emitters.get(satelliteId);
        if (emitter != null) {
            StreamResponse.Builder builder = StreamResponse.newBuilder()
                    .setCommandId(java.util.UUID.randomUUID().toString())
                    .setCommandType(commandType)
                    .setPayload(payload);

            if (traceContext != null) {
                builder.putAllTraceContext(traceContext);
            }

            StreamResponse response = builder.build();
            emitter.emit(response);
            LOG.infof("🚀 Command sent to %s: %s", satelliteId, commandType);
        } else {
            LOG.warnf("⚠️ Cannot send command. Satellite not connected: %s", satelliteId);
        }
    }
}
