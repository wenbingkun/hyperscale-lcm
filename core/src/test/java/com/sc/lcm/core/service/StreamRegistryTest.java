package com.sc.lcm.core.service;

import com.sc.lcm.core.grpc.StreamResponse;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamRegistryTest {
    @Test
    void closingOldConnectionMustNotRemoveReplacementForSameSatellite() {
        StreamRegistry registry = new StreamRegistry();
        List<StreamResponse> received = new ArrayList<>();
        Cancellable oldConnection = Multi.createFrom()
                .<StreamResponse>emitter(emitter -> registry.register("sat-1", emitter))
                .subscribe().with(ignored -> {});
        Cancellable replacement = Multi.createFrom()
                .<StreamResponse>emitter(emitter -> registry.register("sat-1", emitter))
                .subscribe().with(received::add);
        try {
            oldConnection.cancel();
            registry.sendCommand("sat-1", "command-1", "EXEC_SHELL", "true", Map.of());

            assertEquals(1, received.size(), "The replacement connection must still receive commands");
            assertEquals("command-1", received.getFirst().getCommandId());
        } finally {
            oldConnection.cancel();
            replacement.cancel();
        }
    }
}
