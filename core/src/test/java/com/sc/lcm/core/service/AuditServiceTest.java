package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.AuditLog;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class AuditServiceTest {

    @Inject
    AuditService auditService;

    @BeforeEach
    void resetAuditLogs() throws Throwable {
        VertxContextSupport.subscribeAndAwait(
                () -> Panache.withTransaction(() -> AuditLog.deleteAll().replaceWithVoid()));
    }

    @Test
    void logJobScheduledPersistsAuditEntry() throws Throwable {
        VertxContextSupport.subscribeAndAwait(
                () -> auditService.logJobScheduled("job-1", "SYSTEM", "tenant-a", "node-1"));

        List<AuditLog> logs = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> AuditLog.findByResourceId("job-1")));

        assertEquals(1, logs.size());

        AuditLog log = logs.getFirst();
        assertEquals(AuditEventType.JOB_SCHEDULED, log.getEventType());
        assertEquals("JOB", log.getResourceType());
        assertEquals("job-1", log.getResourceId());
        assertEquals("SYSTEM", log.getActor());
        assertEquals("tenant-a", log.getTenantId());
        assertEquals("{\"action\":\"scheduled\",\"nodeId\":\"node-1\"}", log.getDetails());
    }

    @Test
    void logNodeOfflinePersistsSystemAuditEntry() throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> auditService.logNodeOffline("node-9"));

        List<AuditLog> logs = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> AuditLog.findByResourceId("node-9")));

        assertEquals(1, logs.size());

        AuditLog log = logs.getFirst();
        assertEquals(AuditEventType.NODE_OFFLINE, log.getEventType());
        assertEquals("NODE", log.getResourceType());
        assertEquals("SYSTEM", log.getActor());
        assertNull(log.getTenantId());
        assertEquals("{\"action\":\"offline\"}", log.getDetails());
    }
}
