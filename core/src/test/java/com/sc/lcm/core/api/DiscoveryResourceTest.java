package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.service.DeviceClaimPlanner;
import com.sc.lcm.core.service.RedfishClaimExecutor;
import com.sc.lcm.core.service.RedfishManagedAccountProvisioner;
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
import java.util.UUID;

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

    @InjectMock
    RedfishClaimExecutor redfishClaimExecutor;

    @InjectMock
    RedfishManagedAccountProvisioner redfishManagedAccountProvisioner;

    private String authToken;

    @BeforeEach
    void setUp() {
        clearDiscoveredDevices();

        Mockito.when(deviceClaimPlanner.plan(any(DiscoveredDevice.class)))
                .thenAnswer(invocation -> {
                    DiscoveredDevice device = invocation.getArgument(0);
                    return Uni.createFrom().item(device);
                });
        Mockito.when(redfishClaimExecutor.execute(any(), any()))
                .thenReturn(Uni.createFrom().item(new RedfishClaimExecutor.ClaimExecutionResult(
                        true,
                        "https://127.0.0.1:18443",
                        "PROFILE",
                        "OpenBMC",
                        "DemoBMC-9000",
                        "openbmc-baseline",
                        "ok",
                        "BASIC",
                        null,
                        null,
                        java.util.Map.of("accountService", true))));
        Mockito.when(redfishManagedAccountProvisioner.provision(any(), any()))
                .thenReturn(Uni.createFrom().item(new RedfishManagedAccountProvisioner.ManagedAccountProvisionResult(
                        false,
                        false,
                        null,
                        null,
                        null,
                        "managed account disabled",
                        null,
                        null)));

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

    @Test
    void claimEndpointExecutesOnNonBlockingThreadAndReturnsClaimedState() {
        String profileId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        insertCredentialProfile(profileId);
        insertDiscoveredDevice(deviceId, profileId);

        given()
                .header("Authorization", "Bearer " + authToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/discovery/{id}/claim", deviceId)
                .then()
                .statusCode(200)
                .body("claimStatus", equalTo("CLAIMED"))
                .body("authStatus", equalTo("AUTHENTICATED"))
                .body("manufacturerHint", equalTo("OpenBMC"))
                .body("modelHint", equalTo("DemoBMC-9000"));
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
                PreparedStatement deleteDevices = connection.prepareStatement("DELETE FROM discovered_devices");
                PreparedStatement deleteProfiles = connection.prepareStatement("DELETE FROM credential_profiles")) {
            deleteDevices.executeUpdate();
            deleteProfiles.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear discovered devices", e);
        }
    }

    private void insertCredentialProfile(String profileId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO credential_profiles (
                            id, name, protocol, enabled, auto_claim, priority, source_type,
                            device_type, redfish_template, username_secret_ref, password_secret_ref,
                            managed_account_enabled, managed_account_role_id, created_at, updated_at
                        ) VALUES (?, ?, 'REDFISH', true, true, 100, 'MANUAL', ?, ?, ?, ?, false, 'Administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
            insert.setString(1, profileId);
            insert.setString(2, "demo-profile");
            insert.setString(3, "BMC_ENABLED");
            insert.setString(4, "openbmc-baseline");
            insert.setString(5, "literal://admin");
            insert.setString(6, "literal://admin123");
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert credential profile", e);
        }
    }

    private void insertDiscoveredDevice(String deviceId, String profileId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO discovered_devices (
                            id, ip_address, mac_address, discovery_method, status, discovered_at,
                            inferred_type, bmc_address, auth_status, claim_status, credential_profile_id,
                            credential_profile_name, credential_source, claim_message, tenant_id
                        ) VALUES (?, ?, ?, 'DHCP', 'APPROVED', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            insert.setString(1, deviceId);
            insert.setString(2, "127.0.0.1:18443");
            insert.setString(3, "52:54:00:12:34:56");
            insert.setString(4, "BMC_ENABLED");
            insert.setString(5, "127.0.0.1:18443");
            insert.setString(6, "PROFILE_MATCHED");
            insert.setString(7, "READY_TO_CLAIM");
            insert.setString(8, profileId);
            insert.setString(9, "demo-profile");
            insert.setString(10, "PROFILE");
            insert.setString(11, "ready");
            insert.setString(12, "default");
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert discovered device", e);
        }
    }
}
