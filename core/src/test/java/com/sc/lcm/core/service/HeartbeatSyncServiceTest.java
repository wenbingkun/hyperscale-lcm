package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Satellite;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.quarkus.test.hibernate.reactive.panache.TransactionalUniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;

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
        Satellite sat1 = new Satellite();
        sat1.setId("sat-1");
        sat1.setHostname("host1");
        sat1.setStatus("OFFLINE");

        Satellite sat2 = new Satellite();
        sat2.setId("sat-2");
        sat2.setHostname("host2");
        sat2.setStatus("ONLINE");

        // Prepare database state and assertions
        asserter.execute(() -> Satellite.deleteAll())
                .execute(() -> Satellite.persist(sat1))
                .execute(() -> Satellite.persist(sat2))
                .execute(() -> {
                    // sat-1 has a recent heartbeat (comes ONLINE)
                    Mockito.when(stateCache.getLastHeartbeatReactive("sat-1"))
                            .thenReturn(Uni.createFrom().item(System.currentTimeMillis()));

                    // sat-2 has NO heartbeat (goes OFFLINE)
                    Mockito.when(stateCache.getLastHeartbeatReactive("sat-2"))
                            .thenReturn(Uni.createFrom().nullItem());
                })
                .execute(() -> heartbeatSyncService.syncHeartbeatsToDatabase())
                .assertThat(() -> Satellite.<Satellite>findById("sat-1"), result -> {
                    assertNotNull(result);
                    assertEquals("ONLINE", result.getStatus());
                    assertNotNull(result.getLastHeartbeat());
                })
                .assertThat(() -> Satellite.<Satellite>findById("sat-2"), result -> {
                    assertNotNull(result);
                    assertEquals("OFFLINE", result.getStatus());
                });
    }
}
