package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.service.AuditService;
import com.sc.lcm.core.service.BmcClaimWorkflowService;
import com.sc.lcm.core.service.BmcCredentialRotationService;
import com.sc.lcm.core.service.MetricsService;
import com.sc.lcm.core.service.RedfishPowerActionService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
class BmcManagementResourceTest {

    @Inject
    DataSource dataSource;

    @InjectMock
    BmcClaimWorkflowService bmcClaimWorkflowService;

    @InjectMock
    BmcCredentialRotationService bmcCredentialRotationService;

    @InjectMock
    RedfishPowerActionService redfishPowerActionService;

    @InjectMock
    AuditService auditService;

    @InjectMock
    MetricsService metricsService;

    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        clearDiscoveredDevices();
        Mockito.when(auditService.logBmcRotateCredentials(anyString(), anyString(), Mockito.isNull(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());
        Mockito.when(auditService.logBmcPowerAction(
                        anyString(),
                        anyString(),
                        Mockito.isNull(),
                        anyString(),
                        Mockito.nullable(String.class),
                        Mockito.nullable(String.class),
                        anyString(),
                        Mockito.nullable(String.class),
                        anyBoolean()))
                .thenReturn(Uni.createFrom().voidItem());

        adminToken = login("admin", "admin123");
        operatorToken = login("operator", "operator123");
    }

    @AfterEach
    void tearDown() {
        clearDiscoveredDevices();
    }

    @Test
    void capabilitiesEndpointReturnsStoredSnapshotForOperator() {
        String deviceId = UUID.randomUUID().toString();
        insertCapabilitySnapshotDevice(deviceId);

        given()
                .header("Authorization", "Bearer " + operatorToken)
                .when()
                .get("/api/bmc/devices/{id}/capabilities", deviceId)
                .then()
                .statusCode(200)
                .body("deviceId", equalTo(deviceId))
                .body("ipAddress", equalTo("10.0.0.70"))
                .body("bmcAddress", equalTo("10.0.0.170"))
                .body("recommendedRedfishTemplate", equalTo("dell-idrac"))
                .body("lastSuccessfulAuthMode", equalTo("SESSION_PREFERRED"))
                .body("lastAuthFailureCode", equalTo("HTTP_401"))
                .body("capabilities.sessionAuth", equalTo(true))
                .body("capabilities.powerControl", equalTo(true))
                .body("capabilities.systemCount", equalTo(2));
    }

    @Test
    void operatorCannotMutateBmcEndpoints() {
        given()
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/bmc/devices/{id}/claim", "device-1")
                .then()
                .statusCode(403);

        given()
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/bmc/devices/{id}/rotate-credentials", "device-1")
                .then()
                .statusCode(403);

        given()
                .header("Authorization", "Bearer " + operatorToken)
                .header("Idempotency-Key", "operator-test")
                .contentType(ContentType.JSON)
                .body(Map.of("action", "GracefulRestart"))
                .when()
                .post("/api/bmc/devices/{id}/power-actions", "device-1")
                .then()
                .statusCode(403);

        Mockito.verifyNoInteractions(bmcClaimWorkflowService, bmcCredentialRotationService, redfishPowerActionService);
    }

    @Test
    void claimEndpointReturnsManagedStateForAdmin() {
        String deviceId = UUID.randomUUID().toString();
        DiscoveredDevice device = new DiscoveredDevice();
        device.setId(deviceId);
        device.setIpAddress("10.0.0.80");
        device.setBmcAddress("10.0.0.180");
        device.setClaimStatus(DiscoveredDevice.ClaimStatus.CLAIMED);
        device.setAuthStatus(DiscoveredDevice.AuthStatus.AUTHENTICATED);
        device.setManufacturerHint("OpenBMC");
        device.setModelHint("DemoBMC-9000");

        Mockito.when(bmcClaimWorkflowService.claim(eq(deviceId), eq("admin")))
                .thenReturn(Uni.createFrom().item(new BmcClaimWorkflowService.ClaimWorkflowOutcome(
                        deviceId,
                        BmcClaimWorkflowService.Status.OK,
                        null,
                        device,
                        null,
                        null)));

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/bmc/devices/{id}/claim", deviceId)
                .then()
                .statusCode(200)
                .body("id", equalTo(deviceId))
                .body("claimStatus", equalTo("CLAIMED"))
                .body("authStatus", equalTo("AUTHENTICATED"))
                .body("manufacturerHint", equalTo("OpenBMC"))
                .body("modelHint", equalTo("DemoBMC-9000"));
    }

    @Test
    void rotateEndpointReturnsNotModifiedWhenRotationIsSkipped() {
        String deviceId = UUID.randomUUID().toString();
        Mockito.when(bmcCredentialRotationService.rotateDevice(deviceId))
                .thenReturn(Uni.createFrom().item(new BmcCredentialRotationService.RotationResult(
                        deviceId,
                        BmcCredentialRotationService.RotationResult.Status.SKIPPED,
                        "Managed account is disabled.")));

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/bmc/devices/{id}/rotate-credentials", deviceId)
                .then()
                .statusCode(304);
    }

    @Test
    void powerActionEndpointReturnsAcceptedAndLocationHeader() {
        String deviceId = UUID.randomUUID().toString();
        Mockito.when(redfishPowerActionService.execute(
                        eq(deviceId),
                        eq("admin"),
                        eq("power-key-1"),
                        Mockito.argThat(request -> request != null
                                && "GracefulRestart".equals(request.action())
                                && "System-1".equals(request.systemId())),
                        eq(false)))
                .thenReturn(Uni.createFrom().item(new RedfishPowerActionService.PowerActionOutcome(
                        RedfishPowerActionService.Status.ACCEPTED,
                        "GracefulRestart",
                        "System-1",
                        "/redfish/v1/Systems/System-1/Actions/ComputerSystem.Reset",
                        "SESSION_PREFERRED",
                        "/redfish/v1/TaskService/Tasks/42",
                        List.of("GracefulRestart", "ForceRestart"),
                        null,
                        "Power action accepted.",
                        false)));

        given()
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", "power-key-1")
                .contentType(ContentType.JSON)
                .body(Map.of("action", "GracefulRestart", "systemId", "System-1"))
                .when()
                .post("/api/bmc/devices/{id}/power-actions", deviceId)
                .then()
                .statusCode(202)
                .header("Location", equalTo("/redfish/v1/TaskService/Tasks/42"))
                .body("status", equalTo("ACCEPTED"))
                .body("action", equalTo("GracefulRestart"))
                .body("systemId", equalTo("System-1"))
                .body("taskLocation", equalTo("/redfish/v1/TaskService/Tasks/42"))
                .body("authMode", equalTo("SESSION_PREFERRED"))
                .body("targetUri", notNullValue());

        Mockito.verify(metricsService).recordBmcPowerAction("GracefulRestart", "ACCEPTED");
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
                PreparedStatement deleteDevices = connection.prepareStatement("DELETE FROM discovered_devices")) {
            deleteDevices.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear discovered devices", e);
        }
    }

    private void insertCapabilitySnapshotDevice(String deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO discovered_devices (
                            id, ip_address, discovery_method, status, discovered_at, inferred_type,
                            bmc_address, manufacturer_hint, model_hint, recommended_redfish_template,
                            redfish_auth_mode_override, auth_status, claim_status, last_auth_attempt_at,
                            last_successful_auth_mode, last_auth_failure_code, last_auth_failure_reason,
                            bmc_capabilities, last_capability_probe_at, tenant_id
                        ) VALUES (?, ?, 'DHCP', 'APPROVED', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            insert.setString(1, deviceId);
            insert.setString(2, "10.0.0.70");
            insert.setString(3, "BMC");
            insert.setString(4, "10.0.0.170");
            insert.setString(5, "Dell");
            insert.setString(6, "R760");
            insert.setString(7, "dell-idrac");
            insert.setString(8, "SESSION_ONLY");
            insert.setString(9, DiscoveredDevice.AuthStatus.AUTH_FAILED.name());
            insert.setString(10, DiscoveredDevice.ClaimStatus.MANAGED.name());
            insert.setObject(11, LocalDateTime.of(2026, 4, 12, 10, 0));
            insert.setString(12, "SESSION_PREFERRED");
            insert.setString(13, "HTTP_401");
            insert.setString(14, "Session expired during probe.");
            insert.setString(15, "{\"sessionAuth\":true,\"powerControl\":true,\"systemCount\":2}");
            insert.setObject(16, LocalDateTime.of(2026, 4, 12, 10, 5));
            insert.setString(17, "default");
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert capability snapshot device", e);
        }
    }
}
