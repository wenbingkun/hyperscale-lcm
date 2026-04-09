package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class TenantResourceTest {

    @Inject
    DataSource dataSource;

    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        resetTenants();
        adminToken = login("admin", "admin123");
        operatorToken = login("operator", "operator123");
    }

    @AfterEach
    void tearDown() {
        resetTenants();
    }

    @Test
    void createTenantAndReadUsage() {
        TenantResource.CreateTenantRequest request = new TenantResource.CreateTenantRequest(
                "tenant-blue",
                "Tenant Blue",
                "integration test tenant",
                128,
                512L,
                8,
                4);

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/tenants")
                .then()
                .statusCode(201)
                .body("id", equalTo("tenant-blue"))
                .body("name", equalTo("Tenant Blue"))
                .body("gpuQuota", equalTo(8));

        given()
                .header("Authorization", "Bearer " + operatorToken)
                .when()
                .get("/api/tenants/tenant-blue/usage")
                .then()
                .statusCode(200)
                .body("cpuQuota", equalTo(128))
                .body("memoryQuotaGb", equalTo(512))
                .body("gpuQuota", equalTo(8))
                .body("maxConcurrentJobs", equalTo(4));
    }

    @Test
    void createTenantRejectsDuplicateId() {
        TenantResource.CreateTenantRequest request = new TenantResource.CreateTenantRequest(
                "tenant-dup",
                "Tenant Dup",
                "duplicate",
                64,
                256L,
                4,
                2);

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/tenants")
                .then()
                .statusCode(201);

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/tenants")
                .then()
                .statusCode(409)
                .body("error", equalTo("Tenant already exists"));
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

    private void resetTenants() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM tenants")) {
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO tenants (id, name, description, status)
                    VALUES ('default', 'Default Tenant', 'Default system tenant', 'ACTIVE')
                    """)) {
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset tenants", e);
        }
    }
}
