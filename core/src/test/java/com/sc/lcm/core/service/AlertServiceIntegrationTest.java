package com.sc.lcm.core.service;

import com.sc.lcm.core.api.DashboardWebSocket;
import com.sc.lcm.core.support.AlertManagerMockServerResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
@QuarkusTestResource(AlertManagerMockServerResource.class)
class AlertServiceIntegrationTest {

    @Inject
    AlertService alertService;

    @InjectMock
    DashboardWebSocket dashboardWebSocket;

    @InjectMock
    SatelliteStateCache stateCache;

    @BeforeEach
    void setUp() {
        alertService.clearAlerts();
        AlertManagerMockServerResource.clearRequests();
        Mockito.reset(dashboardWebSocket, stateCache);
    }

    @Test
    void raiseNodeOfflineAlertPushesToAlertManager() throws InterruptedException {
        alertService.raiseNodeOfflineAlert("node-9");

        AlertManagerMockServerResource.CapturedRequest request = AlertManagerMockServerResource
                .awaitRequest(Duration.ofSeconds(5));

        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/api/v2/alerts", request.path());
        assertEquals("application/json", request.contentType());
        assertTrue(request.body().contains("\"alertname\":\"NodeOffline\""));
        assertTrue(request.body().contains("\"severity\":\"critical\""));
        assertTrue(request.body().contains("\"source\":\"NodeHealthCheck\""));
        assertTrue(request.body().contains("\"node_id\":\"node-9\""));
        assertTrue(request.body().contains("\"summary\":\"节点 node-9 已离线超过 2 分钟\""));

        Mockito.verify(dashboardWebSocket).broadcastAlert(eq("CRITICAL"), contains("node-9"), eq("NodeHealthCheck"));
    }

    @Test
    void raiseScheduleFailedAlertPushesJobMetadata() throws InterruptedException {
        alertService.raiseScheduleFailedAlert("job-77", "No GPU available");

        AlertManagerMockServerResource.CapturedRequest request = AlertManagerMockServerResource
                .awaitRequest(Duration.ofSeconds(5));

        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/api/v2/alerts", request.path());
        assertTrue(request.body().contains("\"alertname\":\"JobScheduleFailed\""));
        assertTrue(request.body().contains("\"severity\":\"warning\""));
        assertTrue(request.body().contains("\"job_id\":\"job-77\""));
        assertTrue(request.body().contains("\"summary\":\"作业 job-77 调度失败: No GPU available\""));

        Mockito.verify(dashboardWebSocket).broadcastAlert(
                eq("WARNING"),
                eq("作业 job-77 调度失败: No GPU available"),
                eq("Scheduler"));
    }
}
