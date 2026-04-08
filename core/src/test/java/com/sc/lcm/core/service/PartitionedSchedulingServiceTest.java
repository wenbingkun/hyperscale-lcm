package com.sc.lcm.core.service;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.sc.lcm.core.api.DashboardWebSocket;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.LcmSolution;
import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class PartitionedSchedulingServiceTest {

    @Inject
    PartitionedSchedulingService partitionedSchedulingService;

    @InjectMock
    LcmSolverFacade lcmSolverFacade;

    @InjectMock
    NodeSpecsProvider nodeSpecsProvider;

    @InjectMock
    AuditService auditService;

    @InjectMock
    DashboardWebSocket dashboardWebSocket;

    @BeforeEach
    void setUp() {
        Mockito.reset(lcmSolverFacade, nodeSpecsProvider, auditService, dashboardWebSocket);
        Mockito.when(auditService.logJobScheduled(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.anyString()))
                .thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void scheduleByZoneChoosesBestZoneAndPersistsScheduledState() throws Throwable {
        Satellite zoneASatellite = activeSatellite("sat-zone-a", "west", "node-a");
        Satellite zoneBSatellite = activeSatellite("sat-zone-b", "west", "node-b");
        Satellite ignoredClusterSatellite = activeSatellite("sat-other", "east", "node-other");
        Job job = persistedJob("job-zone-best", "tenant-west", "west");

        resetPersistence(zoneASatellite, zoneBSatellite, ignoredClusterSatellite, job);
        mockNode(zoneASatellite.getId(), "zone-a", "A100");
        mockNode(zoneBSatellite.getId(), "zone-b", "H100");
        mockNode(ignoredClusterSatellite.getId(), "zone-ignored", "B200");
        mockSolverOutcomes(Map.of(
                "zone-a", new ZoneOutcome(zoneASatellite.getId(), HardSoftScore.of(1, 0)),
                "zone-b", new ZoneOutcome(zoneBSatellite.getId(), HardSoftScore.of(10, 0))));

        Map<String, Node> result = VertxContextSupport.subscribeAndAwait(() -> partitionedSchedulingService.scheduleByZone(job));

        assertEquals(1, result.size());
        Node assignedNode = result.get(job.getId());
        assertNotNull(assignedNode);
        assertEquals(zoneBSatellite.getId(), assignedNode.getId());
        assertEquals("zone-b", assignedNode.getZoneId());
        assertEquals("H100", assignedNode.getGpuModel());

        Job persistedJob = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> Job.<Job>findByIdReactive(job.getId())));
        assertNotNull(persistedJob);
        assertEquals(Job.JobStatus.SCHEDULED, persistedJob.getStatus());
        assertEquals(zoneBSatellite.getId(), persistedJob.getAssignedNodeId());
        assertNotNull(persistedJob.getScheduledAt());

        Mockito.verify(nodeSpecsProvider).getNodeSpecs(
                Mockito.argThat(satellite -> satellite != null && zoneASatellite.getId().equals(satellite.getId())));
        Mockito.verify(nodeSpecsProvider).getNodeSpecs(
                Mockito.argThat(satellite -> satellite != null && zoneBSatellite.getId().equals(satellite.getId())));
        Mockito.verify(nodeSpecsProvider, Mockito.never()).getNodeSpecs(
                Mockito.argThat(satellite -> satellite != null && ignoredClusterSatellite.getId().equals(satellite.getId())));
        Mockito.verify(auditService).logJobScheduled(
                job.getId(),
                "SYSTEM",
                "tenant-west",
                zoneBSatellite.getId());
    }

    @Test
    void scheduleByZoneLeavesJobPendingWhenNoZoneCanAssign() throws Throwable {
        Satellite zoneASatellite = activeSatellite("sat-zone-empty-a", "west", "node-a");
        Satellite zoneBSatellite = activeSatellite("sat-zone-empty-b", "west", "node-b");
        Job job = persistedJob("job-zone-empty", "tenant-west", "west");

        resetPersistence(zoneASatellite, zoneBSatellite, null, job);
        mockNode(zoneASatellite.getId(), "zone-a", "A100");
        mockNode(zoneBSatellite.getId(), "zone-b", "A100");
        mockSolverOutcomes(Map.of(
                "zone-a", new ZoneOutcome(null, HardSoftScore.of(0, 0)),
                "zone-b", new ZoneOutcome(null, HardSoftScore.of(0, 0))));

        Map<String, Node> result = VertxContextSupport.subscribeAndAwait(() -> partitionedSchedulingService.scheduleByZone(job));

        assertEquals(0, result.size());

        Job persistedJob = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> Job.<Job>findByIdReactive(job.getId())));
        assertNotNull(persistedJob);
        assertEquals(Job.JobStatus.PENDING, persistedJob.getStatus());
        assertEquals(null, persistedJob.getAssignedNodeId());
        assertEquals(null, persistedJob.getScheduledAt());

        Mockito.verify(auditService, Mockito.never()).logJobScheduled(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.anyString());
    }

    private void resetPersistence(Satellite firstSatellite, Satellite secondSatellite, Satellite thirdSatellite, Job job)
            throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> Job.deleteAll()
                .chain(() -> Satellite.deleteAll())
                .chain(() -> Satellite.persist(firstSatellite))
                .chain(() -> Satellite.persist(secondSatellite))
                .chain(() -> thirdSatellite != null ? Satellite.persist(thirdSatellite) : Uni.createFrom().voidItem())
                .chain(() -> job.persist())
                .replaceWithVoid()));
    }

    private void mockNode(String satelliteId, String zoneId, String gpuModel) {
        Mockito.when(nodeSpecsProvider.getNodeSpecs(
                Mockito.argThat(satellite -> satellite != null && satelliteId.equals(satellite.getId()))))
                .thenAnswer(invocation -> {
                    Node node = new Node(satelliteId, 64, 512, 8, gpuModel);
                    node.setZoneId(zoneId);
                    return node;
                });
    }

    private void mockSolverOutcomes(Map<String, ZoneOutcome> outcomes) {
        Mockito.when(lcmSolverFacade.solveBlocking(Mockito.any(LcmSolution.class)))
                .thenAnswer(invocation -> {
                    LcmSolution problem = invocation.getArgument(0);
                    String zoneId = problem.getNodeList().get(0).getZoneId();
                    ZoneOutcome outcome = outcomes.get(zoneId);

                    Job scheduledJob = problem.getJobList().get(0);
                    if (outcome != null && outcome.assignedNodeId() != null) {
                        Node assignedNode = problem.getNodeList().stream()
                                .filter(node -> outcome.assignedNodeId().equals(node.getId()))
                                .findFirst()
                                .orElse(null);
                        scheduledJob.setAssignedNode(assignedNode);
                        scheduledJob.setAssignedNodeId(outcome.assignedNodeId());
                    }
                    problem.setJobList(java.util.List.of(scheduledJob));
                    problem.setScore(outcome != null ? outcome.score() : null);
                    return problem;
                });
    }

    private Satellite activeSatellite(String id, String clusterId, String hostname) {
        Satellite satellite = new Satellite(id, clusterId, hostname, "127.0.0.1", "Linux", "1.0.0");
        satellite.setLastHeartbeat(LocalDateTime.now());
        return satellite;
    }

    private Job persistedJob(String id, String tenantId, String clusterId) {
        Job job = new Job(id, 8, 64, 1, "A100");
        job.setName("Zone Scheduling Test");
        job.setTenantId(tenantId);
        job.setClusterId(clusterId);
        job.setStatus(Job.JobStatus.PENDING);
        return job;
    }

    private record ZoneOutcome(String assignedNodeId, HardSoftScore score) {
    }
}
