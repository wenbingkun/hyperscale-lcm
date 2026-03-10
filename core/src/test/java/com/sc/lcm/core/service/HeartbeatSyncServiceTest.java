package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Satellite;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.hibernate.reactive.panache.TransactionalUniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class HeartbeatSyncServiceTest {

    @Inject
    HeartbeatSyncService heartbeatSyncService;

    @InjectMock
    SatelliteStateCache stateCache;

    @BeforeEach
    public void setup() {
        Mockito.reset(stateCache);
    }

    @Test
    @RunOnVertxContext
    public void testSyncHeartbeatsToDatabase(TransactionalUniAsserter asserter) {
        // Setup mock data
        Satellite sat1 = new Satellite("sat-id1", "default", "host1", "127.0.0.1", "os", "agent");
        sat1.setId("sat-id1");
        sat1.setHostname("host1");
        sat1.setStatus("OFFLINE");

        Satellite sat2 = new Satellite("sat-id2", "default", "host2", "127.0.0.2", "os", "agent");
        sat2.setId("sat-id2");
        sat2.setHostname("host2");
        sat2.setStatus("ONLINE");

        // Prepare database state and assertions
        asserter.execute(() -> Satellite.deleteAll())
                .execute(() -> Satellite.persist(sat1))
                .execute(() -> Satellite.persist(sat2))
                .execute(() -> {
                    // sat-1 has a recent heartbeat (comes ONLINE)
                    Mockito.when(stateCache.getLastHeartbeatReactive("sat-id1"))
                            .thenReturn(Uni.createFrom().item(System.currentTimeMillis()));

                    // sat-2 has NO heartbeat (goes OFFLINE)
                    Mockito.when(stateCache.getLastHeartbeatReactive("sat-id2"))
                            .thenReturn(Uni.createFrom().nullItem());
                })
                .execute(() -> heartbeatSyncService.syncHeartbeatsToDatabase())
                .assertThat(() -> Satellite.<Satellite>findById("sat-id1"), result -> {
                    assertNotNull(result);
                    assertEquals("ONLINE", result.getStatus());
                    assertNotNull(result.getLastHeartbeat());
                })
                .assertThat(() -> Satellite.<Satellite>findById("sat-id2"), result -> {
                    assertNotNull(result);
                    assertEquals("OFFLINE", result.getStatus());
                });
    }
}
