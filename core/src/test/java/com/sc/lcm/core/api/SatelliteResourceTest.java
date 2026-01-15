package com.sc.lcm.core.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * REST API 端点测试
 * 验证 Satellite 相关 API 的基本可用性
 */
@QuarkusTest
public class SatelliteResourceTest {

    @Test
    @DisplayName("测试获取 Satellite 列表端点")
    void testListSatellitesEndpoint() {
        given()
                .when()
                .get("/satellites")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("测试 API 返回有效响应体")
    void testListSatellitesReturnsValidResponse() {
        given()
                .when()
                .get("/satellites")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }
}
