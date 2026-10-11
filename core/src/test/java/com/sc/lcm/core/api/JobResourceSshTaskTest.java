package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.AuditLog;
import com.sc.lcm.core.domain.AuditLog.AuditEventType;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class JobResourceSshTaskTest {

    private String token;

    @BeforeEach
    void login() {
        token = given().contentType(ContentType.JSON)
                .body(new AuthResource.LoginRequest("admin", "admin123", "default"))
                .post("/api/auth/login").then().statusCode(200)
                .extract().as(AuthResource.TokenResponse.class).token();
    }

    private String addDevice() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Private range; the Satellite is never contacted in this test.
        String ip = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
        Response response = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(Map.of("ipAddress", ip, "hostname", "ssh-target", "deviceType", "SERVER"))
                .post("/api/discovery");
        response.then().statusCode(201);
        return response.jsonPath().getString("id");
    }

    private Response submit(Map<String, Object> body) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(body).post("/api/jobs");
    }

    private Map<String, Object> sshTaskBody(String deviceId, String task) {
        return Map.of("name", "ssh-info", "cpuCores", 1, "memoryGb", 1, "gpuCount", 0,
                "clusterId", "default", "executionType", "SSH", "targetDeviceId", deviceId, "task", task);
    }

    @Test
    void unapprovedDeviceIsRejected() {
        String deviceId = addDevice();
        submit(sshTaskBody(deviceId, "SYSTEM_INFO")).then().statusCode(400)
                .body(containsString("not approved"));
    }

    @Test
    void unknownDeviceAndUnknownTaskAreRejected() {
        submit(sshTaskBody("no-such-device", "SYSTEM_INFO")).then().statusCode(404);
        submit(sshTaskBody(addDevice(), "REBOOT")).then().statusCode(400).body(containsString("Unsupported SSH task"));
    }

    @Test
    void inlinePayloadAndMixedRequestsAreRejected() {
        submit(Map.of("name", "inline", "cpuCores", 1, "memoryGb", 1, "gpuCount", 0, "clusterId", "default",
                "executionType", "SSH",
                "executionPayload", "{\"host\":\"h\",\"user\":\"u\",\"password\":\"p\",\"command\":\"id\"}"))
                .then().statusCode(400).body(containsString("Inline SSH payloads are disabled"));

        String deviceId = addDevice();
        approve(deviceId);
        Map<String, Object> mixed = new java.util.HashMap<>(sshTaskBody(deviceId, "SYSTEM_INFO"));
        mixed.put("executionPayload", "{\"command\":\"id\"}");
        submit(mixed).then().statusCode(400).body(containsString("executionPayload must not be set"));
    }

    private void approve(String deviceId) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .post("/api/discovery/" + deviceId + "/approve").then().statusCode(200);
    }

    @Test
    void approvedDeviceYieldsCredentialFreePayloadTargetAndAudit() throws Throwable {
        String deviceId = addDevice();
        approve(deviceId);

        Response created = submit(sshTaskBody(deviceId, "SYSTEM_INFO"));
        created.then().statusCode(201);
        String jobId = created.jsonPath().getString("id");

        given().header("Authorization", "Bearer " + token).get("/api/jobs/" + jobId).then().statusCode(200)
                .body("executionType", equalTo("SSH"))
                .body("targetDeviceId", equalTo(deviceId))
                .body("executionPayload", containsString("\"keyRef\":\"lab_ed25519\""))
                .body("executionPayload", containsString("\"task\":\"SYSTEM_INFO\""))
                .body("executionPayload", containsString("\"user\":\"ops\""))
                .body("executionPayload", not(containsString("password")))
                .body("executionPayload", not(containsString("privateKey")))
                .body("executionPayload", not(containsString("command")));

        List<AuditLog> logs = VertxContextSupport.subscribeAndAwait(
                () -> Panache.withSession(() -> AuditLog.findByResourceId(jobId)));
        assertEquals(1, logs.stream().filter(l -> l.getEventType() == AuditEventType.JOB_SUBMITTED).count());
        AuditLog submitted = logs.stream().filter(l -> l.getEventType() == AuditEventType.JOB_SUBMITTED).findFirst().get();
        assertEquals("admin", submitted.getActor());
        assertTrue(submitted.getDetails().contains("\"targetDeviceId\":\"" + deviceId + "\""));
        assertTrue(submitted.getDetails().contains("\"task\":\"SYSTEM_INFO\""));
        assertTrue(!submitted.getDetails().contains("keyRef"));
    }
}
