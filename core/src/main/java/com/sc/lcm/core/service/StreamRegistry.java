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
            // A late close from an old connection must not remove its replacement.
            emitters.remove(satelliteId, emitter);
            LOG.infof("❌ Satellite disconnected from stream: %s", satelliteId);
        });
    }

    public boolean isConnected(String satelliteId) {
        return emitters.containsKey(satelliteId);
    }

    /** @return true when the command was handed to a live command stream; false otherwise (nothing was sent). */
    public boolean sendCommand(String satelliteId, String commandId, String commandType, String payload,
            Map<String, String> traceContext) {
        MultiEmitter<? super StreamResponse> emitter = emitters.get(satelliteId);
        if (emitter != null) {
            StreamResponse.Builder builder = StreamResponse.newBuilder()
                    .setCommandId(commandId)
                    .setCommandType(commandType)
                    .setPayload(payload);

            if (traceContext != null) {
                builder.putAllTraceContext(traceContext);
            }

            try {
                emitter.emit(builder.build());
            } catch (RuntimeException e) {
                LOG.errorf(e, "Failed to emit command %s to %s", commandId, satelliteId);
                return false;
            }
            LOG.infof("🚀 Command sent to %s: %s", satelliteId, commandType);
            return true;
        }
        LOG.warnf("⚠️ Cannot send command. Satellite not connected: %s", satelliteId);
        return false;
    }
}
