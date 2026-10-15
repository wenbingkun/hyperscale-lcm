package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.AuditLog;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.JobStatusCallback;
import com.sc.lcm.core.service.JobExecutionService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class JobCancelAndCallbackGuardTest {

    @Inject
    JobExecutionService jobExecutionService;

    private String token;

    @BeforeEach
    void login() {
        token = given().contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest("admin", "admin123", "default"))
                .post("/api/auth/login").then().statusCode(200)
                .extract().as(AuthResource.TokenResponse.class).token();
    }

    private String persistJob(JobStatus status) throws Throwable {
        Job job = new Job(UUID.randomUUID().toString(), 1, 1, 0, null);
        job.setName("guard-" + status);
        job.setStatus(status);
        job.setClusterId("default");
        VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(() -> job.persist().replaceWithVoid()));
        return job.getId();
    }

    private Job load(String id) throws Throwable {
        return VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> Job.<Job>findById(id)));
    }

    private void callback(String id, String status, int exitCode) throws Throwable {
        VertxContextSupport.subscribeAndAwait(() -> jobExecutionService.processJobStatusCallback(
                new JobStatusCallback(id, "node-1", status, exitCode, "out", null, null, 0,
                        LocalDateTime.now(), null)));
    }

    private int cancel(String id) {
        return given().header("Authorization", "Bearer " + token).delete("/api/jobs/" + id).statusCode();
    }

    @Test
    void pendingAndScheduledJobsCanBeCancelledAndAreAudited() throws Throwable {
        for (JobStatus status : List.of(JobStatus.PENDING, JobStatus.SCHEDULED)) {
            String id = persistJob(status);
            assertEquals(200, cancel(id));
            assertEquals(JobStatus.CANCELLED, load(id).getStatus());
            List<AuditLog> audit = VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withSession(() -> AuditLog.findByResourceId(id)));
            assertTrue(audit.stream().anyMatch(a -> a.getEventType() == AuditEventType.JOB_CANCELLED
                    && "admin".equals(a.getActor())));
        }
    }

    @Test
    void runningAndFinishedJobsCannotBeCancelled() throws Throwable {
        String running = persistJob(JobStatus.RUNNING);
        given().header("Authorization", "Bearer " + token).delete("/api/jobs/" + running)
                .then().statusCode(409).body(containsString("not supported"));
        assertEquals(JobStatus.RUNNING, load(running).getStatus());

        for (JobStatus status : List.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            assertEquals(409, cancel(persistJob(status)));
        }
        assertEquals(404, cancel("no-such-job"));
    }

    @Test
    void lateRunningCallbackDoesNotOverwriteFinalState() throws Throwable {
        String id = persistJob(JobStatus.SCHEDULED);
        callback(id, "RUNNING", 0);
        callback(id, "COMPLETED", 0);
        callback(id, "RUNNING", 0);
        callback(id, "FAILED", 7);

        Job job = load(id);
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(0, job.getExitCode());
    }

    @Test
    void resultAfterCancelKeepsCancelledAndLeavesAuditEvidence() throws Throwable {
        String id = persistJob(JobStatus.SCHEDULED);
        assertEquals(200, cancel(id));
        callback(id, "COMPLETED", 0);

        assertEquals(JobStatus.CANCELLED, load(id).getStatus());
        List<AuditLog> audit = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> AuditLog.findByResourceId(id)));
        assertTrue(audit.stream().anyMatch(a -> a.getDetails() != null
                && a.getDetails().contains("late_result_after_cancel")));
    }
}
