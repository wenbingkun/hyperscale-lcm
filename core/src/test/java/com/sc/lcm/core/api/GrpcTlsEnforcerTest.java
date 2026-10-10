package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GrpcTlsEnforcerTest.RequireTlsProfile.class)
class GrpcTlsEnforcerTest {

    @Test
    void plaintextGrpcIsRejectedBeforeHandler() {
        given()
                .contentType("application/grpc")
                .when().post("/lcm.LcmService/SendHeartbeat")
                .then()
                .statusCode(200)
                .header("grpc-status", equalTo("7"));
    }

    @Test
    void plaintextRestIsNotAffected() {
        given().when().get("/health/live").then().statusCode(200);
    }

    public static class RequireTlsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("lcm.grpc.require-tls", "true");
        }
    }
}
