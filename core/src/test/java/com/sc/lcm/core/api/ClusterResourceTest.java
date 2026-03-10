package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ClusterResourceTest {

    @Inject
    DataSource dataSource;

    private String authToken;

    @BeforeEach
    void setUp() {
        clearSatellites();

        AuthResource.TokenResponse response = given()
                .contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest("admin", "admin123", "default"))
                .when()
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .as(AuthResource.TokenResponse.class);

        authToken = response.token();
    }

    @Test
    void listClustersAggregatesNodesByCluster() {
        seedSatellite("sat-west-1", "west", LocalDateTime.now());
        seedSatellite("sat-west-2", "west", LocalDateTime.now().minusMinutes(10));
        seedSatellite("sat-east-1", "east", LocalDateTime.now());

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/clusters")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].clusterId", equalTo("east"))
                .body("[0].totalNodes", equalTo(1))
                .body("[0].onlineNodes", equalTo(1))
                .body("[1].clusterId", equalTo("west"))
                .body("[1].totalNodes", equalTo(2))
                .body("[1].onlineNodes", equalTo(1));
    }

    @Test
    void getClusterNodesCanFilterOnlineNodes() {
        seedSatellite("sat-west-1", "west", LocalDateTime.now());
        seedSatellite("sat-west-2", "west", LocalDateTime.now().minusMinutes(10));

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/clusters/west/nodes?status=online")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo("sat-west-1"));

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/clusters/missing")
                .then()
                .statusCode(404);
    }

    private void seedSatellite(String id, String clusterId, LocalDateTime lastHeartbeat) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO satellite (
                        id, cluster_id, hostname, ip_address, os_version, agent_version,
                        status, last_heartbeat, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                Timestamp now = Timestamp.valueOf(LocalDateTime.now());
                insert.setString(1, id);
                insert.setString(2, clusterId);
                insert.setString(3, id);
                insert.setString(4, "127.0.0.1");
                insert.setString(5, "os");
                insert.setString(6, "agent");
                insert.setString(7, "ONLINE");
                insert.setTimestamp(8, Timestamp.valueOf(lastHeartbeat));
                insert.setTimestamp(9, now);
                insert.setTimestamp(10, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed satellite test data", e);
        }
    }

    private void clearSatellites() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement("DELETE FROM satellite")) {
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear satellite test data", e);
        }
    }
}
