package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
public class AuthResourceTest {

    @Test
    public void testLoginAndRefreshFlow() {
        // 1. Login to get initial token
        AuthResource.TokenResponse loginResponse = given()
                .contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest("admin", "admin123", "default"))
                .when()
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract().as(AuthResource.TokenResponse.class);

        String token = loginResponse.token();

        // 2. Use token to refresh
        given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/auth/refresh")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("role", is("ADMIN"));
    }

    @Test
    public void testRefreshUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/auth/refresh")
                .then()
                .statusCode(401);
    }
}
