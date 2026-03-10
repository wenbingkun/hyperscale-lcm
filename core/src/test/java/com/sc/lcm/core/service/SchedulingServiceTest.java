package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Satellite;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.hibernate.reactive.panache.TransactionalUniAsserter;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SchedulingServiceTest {

    @Inject
    SchedulingService schedulingService;

    @InjectMock
    NodeSpecsProvider nodeSpecsProvider;

    @BeforeEach
    void setUp() {
        Mockito.reset(nodeSpecsProvider);
        Mockito.when(nodeSpecsProvider.getNodeSpecs(Mockito.any(Satellite.class)))
                .thenAnswer(invocation -> {
                    Satellite satellite = invocation.getArgument(0);
                    return new com.sc.lcm.core.domain.Node(satellite.getId(), 32, 256, 8, "A100");
                });
    }

    @Test
    @RunOnVertxContext
    void loadProblemReactiveFiltersSatellitesByCluster(TransactionalUniAsserter asserter) {
        Satellite defaultSatellite = new Satellite("sat-default", "default", "host-default", "127.0.0.1", "os", "agent");
        defaultSatellite.setLastHeartbeat(LocalDateTime.now());

        Satellite westSatellite = new Satellite("sat-west", "west", "host-west", "127.0.0.2", "os", "agent");
        westSatellite.setLastHeartbeat(LocalDateTime.now());

        Job job = new Job("job-west", 4, 8, 1, "A100");
        job.setClusterId("west");

        asserter.execute(() -> Satellite.deleteAll())
                .execute(() -> Satellite.persist(defaultSatellite))
                .execute(() -> Satellite.persist(westSatellite))
                .assertThat(() -> schedulingService.loadProblemReactive(job), solution -> {
                    assertEquals(1, solution.getNodeList().size());
                    assertEquals("sat-west", solution.getNodeList().get(0).getId());
                    assertEquals("west", solution.getJobList().get(0).getClusterId());
                });
    }
}
