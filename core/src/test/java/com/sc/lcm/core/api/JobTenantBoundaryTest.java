package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.service.PartitionedSchedulingService;
import com.sc.lcm.core.service.SchedulingService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;

/** The token's tenant_id is the tenant boundary: no impersonation on submit, no cross-tenant reads. */
@QuarkusTest
class JobTenantBoundaryTest {

    @Inject
    DataSource dataSource;

    @InjectMock
    SchedulingService schedulingService;

    @InjectMock
    PartitionedSchedulingService partitionedSchedulingService;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM job")) {
            s.executeUpdate();
        }
        Mockito.when(schedulingService.scheduleJob(any(Job.class))).thenReturn(Uni.createFrom().voidItem());
        Mockito.when(partitionedSchedulingService.scheduleByZone(any(Job.class)))
                .thenReturn(Uni.createFrom().item(Map.of()));
    }

    private String login(String user, String password, String tenant) {
        return given().contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest(user, password, tenant))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");
    }

    private JobResource.JobRequest request(String tenant) {
        return new JobResource.JobRequest("t", "d", 1, 1, 0, null, false, 0, tenant, "default", "SHELL", "echo hi");
    }

    private String submit(String token, String tenant, int expected) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(request(tenant)).post("/api/jobs").then().statusCode(expected).extract().path("id");
    }

    @Test
    void submittingForAnotherTenantIsForbidden() {
        String token = login("operator", "operator123", "tenant-a");
        submit(token, "tenant-b", 403);
    }

    @Test
    void jobTakesTheTokenTenantWhenBodyOmitsIt() {
        String token = login("operator", "operator123", "tenant-a");
        String id = submit(token, null, 201);
        given().header("Authorization", "Bearer " + token).get("/api/jobs/" + id)
                .then().statusCode(200).body("tenantId", org.hamcrest.Matchers.equalTo("tenant-a"));
    }

    @Test
    void otherTenantsCannotListOrReadJobsButAdminCan() {
        String a = login("operator", "operator123", "tenant-a");
        String id = submit(a, "tenant-a", 201);
        String b = login("operator", "operator123", "tenant-b");

        given().header("Authorization", "Bearer " + b).get("/api/jobs/" + id).then().statusCode(404);
        given().header("Authorization", "Bearer " + b).get("/api/jobs?tenantId=tenant-a")
                .then().statusCode(200).body("id", not(hasItem(id)));
        given().header("Authorization", "Bearer " + login("admin", "admin123", "default")).get("/api/jobs/" + id)
                .then().statusCode(200);
    }
}
