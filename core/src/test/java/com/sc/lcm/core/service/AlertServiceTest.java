package com.sc.lcm.core.service;

import com.sc.lcm.core.api.DashboardWebSocket;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
public class AlertServiceTest {

    @Inject
    AlertService alertService;

    @InjectMock
    DashboardWebSocket dashboardWebSocket;

    @InjectMock
    SatelliteStateCache stateCache;

    @BeforeEach
    public void setup() {
        // Clear active alerts simulating a fresh service or via reflection if we must,
        // but since it's ApplicationScoped and mutating, let's just clear manually or
        // through a new method.
        // We will just test the exact behavior. For isolation we want to make sure the
        // alert count is 0.
        // AlertService is ApplicationScoped so it keeps state between tests.
        // Let's add a package-private clearAll() method to AlertService, or just use
        // reflection.
        try {
            java.lang.reflect.Field field = AlertService.class.getDeclaredField("activeAlerts");
            field.setAccessible(true);
            java.util.Map<?, ?> activeAlerts = (java.util.Map<?, ?>) field.get(alertService);
            activeAlerts.clear();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Mockito.reset(dashboardWebSocket);
    }

    @Test
    public void testRaiseNodeOfflineAlert_Success() {
        alertService.raiseNodeOfflineAlert("node-1");

        Mockito.verify(dashboardWebSocket, Mockito.times(1))
                .broadcastAlert(eq("CRITICAL"), anyString(), eq("NodeHealthCheck"));

        assertEquals(1, alertService.getActiveAlertCount());
    }

    @Test
    public void testRaiseNodeOfflineAlert_Suppression() {
        // Fire first alert
        alertService.raiseNodeOfflineAlert("node-2");
        // Fire second alert immediately (should be suppressed)
        alertService.raiseNodeOfflineAlert("node-2");

        Mockito.verify(dashboardWebSocket, Mockito.times(1))
                .broadcastAlert(eq("CRITICAL"), anyString(), eq("NodeHealthCheck"));

        assertEquals(1, alertService.getActiveAlertCount());
    }

    @Test
    public void testRaiseScheduleFailedAlert() {
        alertService.raiseScheduleFailedAlert("job-123", "No GPU available");

        Mockito.verify(dashboardWebSocket, Mockito.times(1))
                .broadcastAlert(eq("WARNING"), eq("作业 job-123 调度失败: No GPU available"), eq("Scheduler"));

        assertEquals(1, alertService.getActiveAlertCount());
    }

    @Test
    public void testRaiseGpuOverheatAlert() {
        alertService.raiseGpuOverheatAlert("node-3", 95);

        Mockito.verify(dashboardWebSocket, Mockito.times(1))
                .broadcastAlert(eq("WARNING"), eq("节点 node-3 GPU 温度过高: 95°C"), eq("Telemetry"));

        assertEquals(1, alertService.getActiveAlertCount());
    }
}
