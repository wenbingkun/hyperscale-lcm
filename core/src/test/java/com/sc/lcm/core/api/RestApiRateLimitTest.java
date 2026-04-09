package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(RestApiRateLimitTest.RateLimitProfile.class)
class RestApiRateLimitTest {

    @Inject
    RestApiRateLimiter rateLimiter;

    private String userToken;
    private String operatorToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        rateLimiter.clearLimits();
        userToken = login("user", "user123");
        operatorToken = login("operator", "operator123");
        adminToken = login("admin", "admin123");
    }

    @AfterEach
    void tearDown() {
        rateLimiter.clearLimits();
    }

    @Test
    void userLimitAppliesPerPrincipal() {
        assertStatsRequest(userToken, 200);
        assertStatsRequest(userToken, 200);
        assertStatsRequest(userToken, 429);

        String secondUserToken = issueToken("user-two", "USER");
        assertStatsRequest(secondUserToken, 200);
    }

    @Test
    void operatorLimitAllowsConfiguredBurst() {
        assertStatsRequest(operatorToken, 200);
        assertStatsRequest(operatorToken, 200);
        assertStatsRequest(operatorToken, 200);
        assertStatsRequest(operatorToken, 429);
    }

    @Test
    void adminLimitAllowsHighestBurst() {
        assertStatsRequest(adminToken, 200);
        assertStatsRequest(adminToken, 200);
        assertStatsRequest(adminToken, 200);
        assertStatsRequest(adminToken, 200);
        assertStatsRequest(adminToken, 429);
    }

    private void assertStatsRequest(String token, int expectedStatus) {
        var response = given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/jobs/stats");

        response.then().statusCode(expectedStatus);
        if (expectedStatus == 429) {
            response.then()
                    .header("Retry-After", notNullValue())
                    .contentType(ContentType.JSON)
                    .body("error", equalTo("API rate limit exceeded for role " + extractRole(token)));
        }
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

    private String issueToken(String username, String role) {
        return Jwt.issuer("https://lcm.example.com")
                .upn(username)
                .groups(Set.of(role))
                .claim("tenant_id", "default")
                .expiresIn(Duration.ofHours(1))
                .sign();
    }

    private String extractRole(String token) {
        if (token.equals(adminToken)) {
            return "ADMIN";
        }
        if (token.equals(operatorToken)) {
            return "OPERATOR";
        }
        return "USER";
    }

    public static class RateLimitProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "lcm.api.rate-limit.user.requests", "2",
                    "lcm.api.rate-limit.operator.requests", "3",
                    "lcm.api.rate-limit.admin.requests", "4");
        }
    }
}
