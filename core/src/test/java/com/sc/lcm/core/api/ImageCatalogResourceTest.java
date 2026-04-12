package com.sc.lcm.core.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ImageCatalogResourceTest {

    @Inject
    DataSource dataSource;

    private String authToken;
    private static HttpServer imageApiServer;

    @BeforeAll
    static void startImageApiServer() throws IOException {
        imageApiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 18095), 0);
        imageApiServer.createContext("/api/images", ImageCatalogResourceTest::handleImageCatalogRequest);
        imageApiServer.start();
    }

    @AfterAll
    static void stopImageApiServer() {
        if (imageApiServer != null) {
            imageApiServer.stop(0);
        }
    }

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
    void listImagesAggregatesCatalogForActiveSatellites() {
        seedSatellite("sat-west-1", "west", "127.0.0.1", LocalDateTime.now());
        seedSatellite("sat-west-stale", "west", "127.0.0.1", LocalDateTime.now().minusMinutes(10));

        given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/api/images?clusterId=west")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].satelliteId", equalTo("sat-west-1"))
                .body("[0].clusterId", equalTo("west"))
                .body("[0].online", equalTo(true))
                .body("[0].reachable", equalTo(true))
                .body("[0].images", hasSize(1))
                .body("[0].images[0].name", equalTo("ubuntu-24.04.iso"))
                .body("[0].images[0].sizeBytes", equalTo(2048));
    }

    private static void handleImageCatalogRequest(HttpExchange exchange) throws IOException {
        byte[] body = """
                [
                  {
                    "name": "ubuntu-24.04.iso",
                    "sizeBytes": 2048,
                    "contentType": "application/octet-stream",
                    "lastModifiedEpochMs": 1775721600000
                  }
                ]
                """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private void seedSatellite(String id, String clusterId, String ipAddress, LocalDateTime lastHeartbeat) {
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
                insert.setString(4, ipAddress);
                insert.setString(5, "ubuntu");
                insert.setString(6, "1.0.0");
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
