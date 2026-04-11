package com.sc.lcm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.RedfishAuthMode;
import com.sc.lcm.core.service.RedfishPowerActionService.PowerActionOutcome;
import com.sc.lcm.core.service.RedfishPowerActionService.PowerActionRequest;
import com.sc.lcm.core.service.RedfishPowerActionService.Status;
import com.sc.lcm.core.support.RedfishMockServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedfishPowerActionServiceTest {

    private static final String SYSTEMS_URI = "/redfish/v1/Systems";
    private static final String SYSTEM_URI = "/redfish/v1/Systems/system";
    private static final String RESET_TARGET = "/redfish/v1/Systems/system/Actions/ComputerSystem.Reset";

    @Test
    @DisplayName("dryRun=true 返回目标 URI 与解析后的 systemId，不发起破坏性 POST")
    void dryRunReturnsTargetWithoutMutation() throws Exception {
        try (RedfishMockServer server = singleSystemServerBuilder().build()) {
            RedfishPowerActionService service = newService();

            PowerActionOutcome outcome = service.performBlocking(
                    optionsFor(server),
                    "ForceOff",
                    null,
                    true,
                    "device-001",
                    "tester");

            assertEquals(Status.DRY_RUN, outcome.status());
            assertEquals("ForceOff", outcome.action());
            assertEquals("system", outcome.systemId());
            assertEquals(RESET_TARGET, outcome.targetUri());
            assertTrue(outcome.allowedValues().containsAll(List.of("On", "ForceOff", "GracefulShutdown")));
            assertTrue(server.findRequest("POST", RESET_TARGET).isEmpty(),
                    "dry-run must not POST the reset action");
        }
    }

    @Test
    @DisplayName("BMC 返回 204 时归类为 COMPLETED")
    void completedWhenBmcReturnsNoContent() throws Exception {
        try (RedfishMockServer server = singleSystemServerBuilder()
                .withActionResponse(RESET_TARGET, 204, null, null)
                .build()) {
            RedfishPowerActionService service = newService();

            PowerActionOutcome outcome = service.performBlocking(
                    optionsFor(server),
                    "On",
                    null,
                    false,
                    "device-001",
                    "tester");

            assertEquals(Status.COMPLETED, outcome.status());
            assertEquals("On", outcome.action());
            assertNull(outcome.taskLocation());
            assertTrue(server.findRequest("POST", RESET_TARGET).isPresent());
        }
    }

    @Test
    @DisplayName("BMC 返回 202 + Location 时透传为 ACCEPTED 与 taskLocation")
    void acceptedReturnsTaskLocation() throws Exception {
        try (RedfishMockServer server = singleSystemServerBuilder()
                .withActionResponse(RESET_TARGET, 202, "/redfish/v1/TaskService/Tasks/42",
                        Map.of("Id", "42", "TaskState", "New"))
                .build()) {
            RedfishPowerActionService service = newService();

            PowerActionOutcome outcome = service.performBlocking(
                    optionsFor(server),
                    "GracefulShutdown",
                    null,
                    false,
                    "device-001",
                    "tester");

            assertEquals(Status.ACCEPTED, outcome.status());
            assertEquals("/redfish/v1/TaskService/Tasks/42", outcome.taskLocation());
        }
    }

    @Test
    @DisplayName("多 ComputerSystem 且未指定 systemId 时返回 MULTIPLE_SYSTEMS_REQUIRE_SYSTEM_ID")
    void multipleSystemsRequireExplicitId() throws Exception {
        Map<String, Object> systemsCollection = Map.of(
                "Members", List.of(
                        Map.of("@odata.id", "/redfish/v1/Systems/blade1"),
                        Map.of("@odata.id", "/redfish/v1/Systems/blade2")));
        try (RedfishMockServer server = baseServerBuilder()
                .withFixtureOverride(SYSTEMS_URI, systemsCollection)
                .withFixtureOverride("/redfish/v1/Systems/blade1",
                        systemDocument("/redfish/v1/Systems/blade1",
                                "/redfish/v1/Systems/blade1/Actions/ComputerSystem.Reset"))
                .withFixtureOverride("/redfish/v1/Systems/blade2",
                        systemDocument("/redfish/v1/Systems/blade2",
                                "/redfish/v1/Systems/blade2/Actions/ComputerSystem.Reset"))
                .build()) {
            RedfishPowerActionService service = newService();

            PowerActionOutcome outcome = service.performBlocking(
                    optionsFor(server),
                    "GracefulRestart",
                    null,
                    true,
                    "device-multi",
                    "tester");

            assertEquals(Status.BAD_REQUEST, outcome.status());
            assertEquals("MULTIPLE_SYSTEMS_REQUIRE_SYSTEM_ID", outcome.failureCode());
        }
    }

    @Test
    @DisplayName("Phase 7 不允许 PushPowerButton/Nmi/PowerCycle，被请求层拦截")
    void rejectsActionOutsideAllowedSet() {
        RedfishPowerActionService service = newService();
        PowerActionOutcome outcome = service.execute(
                "device-001",
                "tester",
                "key-1",
                new PowerActionRequest("PushPowerButton", null),
                false).await().indefinitely();

        assertEquals(Status.BAD_REQUEST, outcome.status());
        assertEquals("UNSUPPORTED_ACTION", outcome.failureCode());
    }

    @Test
    @DisplayName("缺失 Idempotency-Key 时立即返回 400")
    void missingIdempotencyKeyRejected() {
        RedfishPowerActionService service = newService();
        PowerActionOutcome outcome = service.execute(
                "device-001",
                "tester",
                null,
                new PowerActionRequest("On", null),
                false).await().indefinitely();

        assertEquals(Status.BAD_REQUEST, outcome.status());
        assertEquals("MISSING_IDEMPOTENCY_KEY", outcome.failureCode());
    }

    @Test
    @DisplayName("BMC 显式拒绝某个 ResetType 时返回 ACTION_NOT_SUPPORTED_BY_BMC")
    void rejectsActionNotInBmcAllowedValues() throws Exception {
        Map<String, Object> systemDoc = systemDocument(SYSTEM_URI, RESET_TARGET);
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) systemDoc.get("Actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> resetAction = (Map<String, Object>) actions.get("#ComputerSystem.Reset");
        resetAction.put("ResetType@Redfish.AllowableValues", List.of("On", "GracefulShutdown"));
        try (RedfishMockServer server = baseServerBuilder()
                .withFixtureOverride(SYSTEM_URI, systemDoc)
                .build()) {
            RedfishPowerActionService service = newService();

            PowerActionOutcome outcome = service.performBlocking(
                    optionsFor(server),
                    "ForceOff",
                    null,
                    true,
                    "device-001",
                    "tester");

            assertEquals(Status.BAD_REQUEST, outcome.status());
            assertEquals("ACTION_NOT_SUPPORTED_BY_BMC", outcome.failureCode());
        }
    }

    private static RedfishMockServer.Builder baseServerBuilder() {
        return RedfishMockServer.builder()
                .withFixture("openbmc-baseline");
    }

    private static RedfishMockServer.Builder singleSystemServerBuilder() {
        return baseServerBuilder()
                .withFixtureOverride(SYSTEM_URI, systemDocument(SYSTEM_URI, RESET_TARGET));
    }

    private static Map<String, Object> systemDocument(String odataId, String resetTarget) {
        Map<String, Object> resetAction = new LinkedHashMap<>();
        resetAction.put("target", resetTarget);
        resetAction.put("ResetType@Redfish.AllowableValues",
                List.of("On", "ForceOff", "GracefulShutdown", "GracefulRestart", "ForceRestart"));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("#ComputerSystem.Reset", resetAction);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@odata.id", odataId);
        document.put("Manufacturer", "OpenBMC");
        document.put("Model", "OpenBMC Reference Board");
        document.put("PowerState", "On");
        document.put("Actions", actions);
        return document;
    }

    private static RedfishTransport.RequestOptions optionsFor(RedfishMockServer server) {
        return new RedfishTransport.RequestOptions(
                server.endpoint(),
                "admin",
                "password",
                true,
                2000,
                5000,
                RedfishAuthMode.BASIC_ONLY);
    }

    private static RedfishPowerActionService newService() {
        RedfishPowerActionService service = new RedfishPowerActionService();
        service.redfishTransport = newRedfishTransport();
        service.secretRefResolver = new SecretRefResolver();
        service.connectTimeoutMs = 2000;
        service.readTimeoutMs = 5000;
        service.insecure = true;
        service.defaultAuthMode = RedfishAuthMode.BASIC_ONLY.name();
        service.idempotencyTtlSeconds = 60;
        return service;
    }

    private static RedfishTransport newRedfishTransport() {
        RedfishTransport transport = new RedfishTransport();
        transport.objectMapper = new ObjectMapper();
        transport.sessionManager = new RedfishSessionManager();
        return transport;
    }
}
