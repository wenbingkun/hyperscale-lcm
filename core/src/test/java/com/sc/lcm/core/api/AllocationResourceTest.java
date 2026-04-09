package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.service.PartitionedSchedulingService;
import com.sc.lcm.core.service.SchedulingService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class AllocationResourceTest {

    @Inject
    DataSource dataSource;

    @InjectMock
    SchedulingService schedulingService;

    @InjectMock
    PartitionedSchedulingService partitionedSchedulingService;

    private String authToken;

    @BeforeEach
    void setUp() {
        clearJobs();

        Mockito.when(schedulingService.scheduleJob(any(Job.class)))
                .thenReturn(Uni.createFrom().voidItem());
        Mockito.when(partitionedSchedulingService.scheduleByZone(any(Job.class)))
                .thenReturn(Uni.createFrom().item(Map.of()));

        authToken = login("user", "user123");
    }

    @AfterEach
    void tearDown() {
        clearJobs();
    }

    @Test
    void requestAllocationPersistsPendingJob() {
        AllocationResource.AllocationResponse response = given()
                .header("Authorization", "Bearer " + authToken)
                .contentType(ContentType.JSON)
                .body(new AllocationResource.AllocationRequest(32, 256L, 4, "H100", true, 300, "default", "default"))
                .when()
                .post("/api/v1/allocations")
                .then()
                .statusCode(202)
                .body("allocationId", notNullValue())
                .body("status", equalTo("PENDING"))
                .extract()
                .as(AllocationResource.AllocationResponse.class);

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/v1/allocations/" + response.allocationId())
                .then()
                .statusCode(200)
                .body("allocationId", equalTo(response.allocationId()))
                .body("status", equalTo("PENDING"));
    }

    @Test
    void getAllocationStatusReturnsNotFoundForMissingRecord() {
        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/v1/allocations/missing-allocation")
                .then()
                .statusCode(404)
                .body("error", equalTo("Allocation not found: missing-allocation"));
    }

    private String login(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest(username, password, "default"))
                .when()
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
    }

    private void clearJobs() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement("DELETE FROM job")) {
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear jobs", e);
        }
    }
}
