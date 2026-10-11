package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class JobSubmissionAuditFailureTest {
    @Inject DataSource dataSource;

    @AfterEach
    void removeAuditFault() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS job_submit_audit_test_fault ON audit_log");
            s.execute("DROP FUNCTION IF EXISTS job_submit_audit_test_reject()");
        }
    }

    @Test
    void failedSubmissionAuditMustNotReturn201OrLeaveAnExecutableJob() throws Exception {
        String token = given().contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest("admin", "admin123", "default"))
                .post("/api/auth/login").then().statusCode(200)
                .extract().as(AuthResource.TokenResponse.class).token();
        String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "."
                + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(1, 255);
        String deviceId = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(Map.of("ipAddress", ip, "hostname", "review-audit-target", "deviceType", "SERVER"))
                .post("/api/discovery").then().statusCode(201).extract().jsonPath().getString("id");
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .post("/api/discovery/" + deviceId + "/approve").then().statusCode(200);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE FUNCTION job_submit_audit_test_reject() RETURNS trigger LANGUAGE plpgsql AS "
                    + "'BEGIN IF NEW.event_type = ''JOB_SUBMITTED'' THEN RAISE EXCEPTION ''review injected audit write failure''; END IF; RETURN NEW; END'");
            s.execute("CREATE TRIGGER job_submit_audit_test_fault BEFORE INSERT ON audit_log "
                    + "FOR EACH ROW EXECUTE FUNCTION job_submit_audit_test_reject()");
        }
        String jobName = "audit-failure-" + UUID.randomUUID();
        Response response = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(Map.of("name", jobName, "cpuCores", 1, "memoryGb", 1, "gpuCount", 0,
                        "clusterId", "review-audit-" + UUID.randomUUID(), "tenantId", "default",
                        "executionType", "SSH", "targetDeviceId", deviceId, "task", "SYSTEM_INFO"))
                .post("/api/jobs");
        int status = response.statusCode();
        long jobs, audits;
        try (Connection c = dataSource.getConnection();
                PreparedStatement q = c.prepareStatement("SELECT (SELECT count(*) FROM job WHERE name=?), "
                        + "(SELECT count(*) FROM audit_log WHERE resource_id IN (SELECT id FROM job WHERE name=?) "
                        + "AND event_type='JOB_SUBMITTED')")) {
            q.setString(1, jobName); q.setString(2, jobName);
            try (var rs = q.executeQuery()) { rs.next(); jobs = rs.getLong(1); audits = rs.getLong(2); }
        }
        assertTrue(status >= 500 && status < 600 && jobs == 0 && audits == 0,
                "Actual SQL audit failure: HTTP=" + status + ", persisted jobs=" + jobs + ", submission audits=" + audits);
    }
}
