package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.service.DeviceClaimPlanner;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class DiscoveryResourceTest {

    @Inject
    DataSource dataSource;

    @InjectMock
    DeviceClaimPlanner deviceClaimPlanner;

    private String authToken;

    @BeforeEach
    void setUp() {
        clearDiscoveredDevices();

        Mockito.when(deviceClaimPlanner.plan(any(DiscoveredDevice.class)))
                .thenAnswer(invocation -> {
                    DiscoveredDevice device = invocation.getArgument(0);
                    return Uni.createFrom().item(device);
                });

        authToken = login("admin", "admin123");
    }

    @AfterEach
    void tearDown() {
        clearDiscoveredDevices();
    }

    @Test
    void addDeviceCreatesPendingRecordAndUpdatesPendingCount() {
        DiscoveryResource.AddDeviceRequest request = new DiscoveryResource.AddDeviceRequest(
                "10.0.0.50",
                "bmc-01",
                "AA:BB:CC:DD:EE:FF",
                "BMC_ENABLED",
                "Dell",
                "PowerEdge",
                "test import",
                "default");

        given()
                .header("Authorization", "Bearer " + authToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/discovery")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("ipAddress", equalTo("10.0.0.50"))
                .body("status", equalTo("PENDING"))
                .body("bmcAddress", equalTo("10.0.0.50"));

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/discovery/pending/count")
                .then()
                .statusCode(200)
                .body("count", equalTo(1));
    }

    @Test
    void addDeviceRejectsDuplicateIp() {
        DiscoveryResource.AddDeviceRequest request = new DiscoveryResource.AddDeviceRequest(
                "10.0.0.51",
                "node-dup",
                "AA:BB:CC:00:00:51",
                "COMPUTE_NODE",
                "NVIDIA",
                "HGX",
                "duplicate test",
                "default");

        given()
                .header("Authorization", "Bearer " + authToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/discovery")
                .then()
                .statusCode(201);

        given()
                .header("Authorization", "Bearer " + authToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/discovery")
                .then()
                .statusCode(409)
                .body("error", equalTo("Device already exists: 10.0.0.51"));
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

    private void clearDiscoveredDevices() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement("DELETE FROM discovered_devices")) {
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear discovered devices", e);
        }
    }
}
