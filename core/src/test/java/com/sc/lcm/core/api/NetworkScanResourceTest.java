package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.ScanJob;
import com.sc.lcm.core.service.NetworkScanService;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class NetworkScanResourceTest {

    @Inject
    DataSource dataSource;

    @InjectMock
    NetworkScanService networkScanService;

    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        clearScanJobs();

        Mockito.when(networkScanService.executeScan(any(ScanJob.class)))
                .thenReturn(Uni.createFrom().voidItem());

        adminToken = login("admin", "admin123");
        operatorToken = login("operator", "operator123");
    }

    @AfterEach
    void tearDown() {
        clearScanJobs();
    }

    @Test
    void startScanPersistsTaskForAdmin() {
        NetworkScanResource.ScanResponse response = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(new NetworkScanResource.StartScanRequest("10.0.0.0/30", "22,443", "default"))
                .when()
                .post("/api/scan")
                .then()
                .statusCode(202)
                .body("id", notNullValue())
                .body("message", equalTo("Scan started"))
                .extract()
                .as(NetworkScanResource.ScanResponse.class);

        given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get("/api/scan")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo(response.id()))
                .body("[0].target", equalTo("10.0.0.0/30"))
                .body("[0].createdBy", equalTo("admin"));
    }

    @Test
    void operatorCannotStartScan() {
        given()
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(ContentType.JSON)
                .body(new NetworkScanResource.StartScanRequest("10.0.1.0/30", "22", "default"))
                .when()
                .post("/api/scan")
                .then()
                .statusCode(403);
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

    private void clearScanJobs() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement("DELETE FROM scan_jobs")) {
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear scan jobs", e);
        }
    }
}
