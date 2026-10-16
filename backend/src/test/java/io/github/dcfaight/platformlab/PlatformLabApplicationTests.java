package io.github.dcfaight.platformlab;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class PlatformLabApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM operations");
        jdbc.update("DELETE FROM environments");
    }

    @Test
    void creationPersistsCompleteEnvironmentOperationAndAudit() throws Exception {
        Instant before = Instant.now();
        ApiResponse created = request(port, "POST", "/api/v1/environments", """
                {"name":"  Preview 123_-  ","templateId":"sample-service","ttlHours":3}
                """, Map.of("Idempotency-Key", "create-complete", "X-Actor", "caller-selected-actor"));
        Instant after = Instant.now();

        assertEquals(202, created.status());
        assertFields(created.json(), "environmentId", "operationId", "environmentStatus", "operationStatus");
        String environmentId = uuid(created.json(), "environmentId");
        String operationId = uuid(created.json(), "operationId");
        assertEquals("REQUESTED", created.json().path("environmentStatus").asText());
        assertEquals("PENDING", created.json().path("operationStatus").asText());
        assertEquals("/api/v1/operations/" + operationId, created.header("Location"));

        ApiResponse environment = get("/api/v1/environments/" + environmentId);
        assertEquals(200, environment.status());
        assertFields(environment.json(), "id", "name", "namespace", "templateId", "status",
                "expiresAt", "createdAt", "updatedAt");
        assertEquals(environmentId, environment.json().path("id").asText());
        assertEquals("Preview 123_-", environment.json().path("name").asText());
        assertEquals("env-" + environmentId.replace("-", ""), environment.json().path("namespace").asText());
        assertEquals("sample-service", environment.json().path("templateId").asText());
        assertEquals("REQUESTED", environment.json().path("status").asText());
        assertBetween(timestamp(environment.json(), "createdAt"), before, after);
        assertBetween(timestamp(environment.json(), "updatedAt"), before, after);
        assertBetween(timestamp(environment.json(), "expiresAt"),
                before.plus(Duration.ofHours(3)), after.plus(Duration.ofHours(3)));

        ApiResponse operation = get("/api/v1/operations/" + operationId);
        assertEquals(200, operation.status());
        assertFields(operation.json(), "id", "environmentId", "type", "status", "attemptCount",
                "nextAttemptAt", "leaseExpiresAt", "startedAt", "completedAt", "createdAt", "updatedAt",
                "failureCode", "failureMessage", "progressStep", "correlationId", "events");
        assertEquals(created.header("X-Request-Id"), operation.json().path("correlationId").asText());
        assertEquals("ACCEPTED", operation.json().path("progressStep").asText());
        assertTrue(operation.json().path("events").isEmpty());
        assertEquals(operationId, operation.json().path("id").asText());
        assertEquals(environmentId, operation.json().path("environmentId").asText());
        assertEquals("CREATE_ENVIRONMENT", operation.json().path("type").asText());
        assertEquals("PENDING", operation.json().path("status").asText());
        assertEquals(0, operation.json().path("attemptCount").asInt());
        for (String field : List.of("leaseExpiresAt", "startedAt", "completedAt")) {
            assertTrue(operation.json().path(field).isNull(), field);
        }
        for (String field : List.of("nextAttemptAt", "createdAt", "updatedAt")) {
            assertBetween(timestamp(operation.json(), field), before, after);
        }

        assertCounts(1);
        Map<String, Object> reservation = jdbc.queryForMap(
                "SELECT * FROM idempotency_requests WHERE key = ?", "create-complete");
        assertEquals("Preview 123_-", reservation.get("name"));
        assertEquals("sample-service", reservation.get("template_id"));
        assertEquals(3, ((Number) reservation.get("ttl_hours")).intValue());
        assertEquals(environmentId, reservation.get("environment_id").toString());
        assertEquals(operationId, reservation.get("operation_id").toString());
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_events");
        assertEquals(environmentId, audit.get("environment_id").toString());
        assertEquals(operationId, audit.get("operation_id").toString());
        assertEquals("ENVIRONMENT_REQUESTED", audit.get("action"));
        assertEquals("local-development", audit.get("actor"));
        assertNotNull(audit.get("id"));
        assertNotNull(audit.get("created_at"));
    }

    @Test
    void equivalentNormalizedRequestsReplayWithoutNewWrites() throws Exception {
        ApiResponse original = post("same-normalized", """
                {"name":"  Preview  ","templateId":"sample-service"}
                """);
        assertEquals(202, original.status());
        for (String body : List.of(
                """
                {"name":"Preview","templateId":"sample-service","ttlHours":8}
                """,
                """
                {"name":" Preview ","templateId":"sample-service","ttlHours":null}
                """,
                """
                {"name":"Preview","templateId":"sample-service"}
                """)) {
            ApiResponse replay = post("same-normalized", body);
            assertEquals(202, replay.status());
            assertEquals(original.json(), replay.json());
            assertEquals(original.header("Location"), replay.header("Location"));
        }
        JsonNode environment = get("/api/v1/environments/" + original.json().path("environmentId").asText()).json();
        assertEquals(Duration.ofHours(8),
                Duration.between(timestamp(environment, "createdAt"), timestamp(environment, "expiresAt")));
        assertCounts(1);
    }

    @Test
    void differentPayloadForExistingKeyConflictsWithoutChangingOriginal() throws Exception {
        ApiResponse original = post("conflict", payload("Original", 8));
        assertEquals(202, original.status());
        assertError(post("conflict", payload("Different", 8)), 409);
        assertError(post("conflict", payload("Original", 9)), 409);
        assertEquals(original.json(), post("conflict", payload("Original", 8)).json());
        assertEquals("Original", jdbc.queryForObject("SELECT name FROM environments", String.class));
        assertCounts(1);
    }

    @Test
    void samePayloadWithDifferentKeysCreatesIndependentResources() throws Exception {
        ApiResponse first = post("first-key", payload("Same name", 1));
        ApiResponse second = post("second-key", payload("Same name", 1));
        assertEquals(202, first.status());
        assertEquals(202, second.status());
        assertNotEquals(first.json().path("environmentId"), second.json().path("environmentId"));
        assertNotEquals(first.json().path("operationId"), second.json().path("operationId"));
        assertCounts(2);
    }

    @Test
    void acceptsNameTtlAndIdempotencyKeyBoundaryValues() throws Exception {
        List<String> names = List.of("A", "0", "a".repeat(63), "Abc XYZ_9-");
        for (int index = 0; index < names.size(); index++) {
            String key = index == 0 ? "a" : index == 1 ? "Z" + "a._:-9".repeat(21) + "x" : "valid-" + index;
            int ttl = index % 2 == 0 ? 1 : 24;
            ApiResponse response = post(key, payload(names.get(index), ttl));
            assertEquals(202, response.status(), response.body());
            JsonNode environment = get("/api/v1/environments/" + response.json().path("environmentId").asText()).json();
            assertEquals(names.get(index), environment.path("name").asText());
            assertEquals(Duration.ofHours(ttl),
                    Duration.between(timestamp(environment, "createdAt"), timestamp(environment, "expiresAt")));
        }
        assertCounts(names.size());
    }

    @Test
    void rejectsInvalidNamesWithoutPersistingAnything() throws Exception {
        for (String name : List.of("", "   ", "a".repeat(64), "_abc", "-abc", "éclair",
                "abc/def", "abc.def", "abc\tdef", "abc\ndef", "abc😀")) {
            ApiResponse response = post("invalid-name", mapper.writeValueAsString(
                    Map.of("name", name, "templateId", "sample-service")));
            assertError(response, 400);
            assertFieldError(response, "name");
        }
        for (String body : List.of(
                "{\"templateId\":\"sample-service\"}",
                "{\"name\":null,\"templateId\":\"sample-service\"}")) {
            ApiResponse response = post("missing-name", body);
            assertError(response, 400);
            assertFieldError(response, "name");
        }
        assertCounts(0);
    }

    @Test
    void rejectsMissingOrUnsupportedTemplates() throws Exception {
        for (String body : List.of(
                "{\"name\":\"Preview\"}",
                "{\"name\":\"Preview\",\"templateId\":null}",
                "{\"name\":\"Preview\",\"templateId\":\"\"}",
                "{\"name\":\"Preview\",\"templateId\":\"other-service\"}",
                "{\"name\":\"Preview\",\"templateId\":\" sample-service \"}")) {
            ApiResponse response = post("invalid-template", body);
            assertError(response, 400);
            assertFieldError(response, "templateId");
        }
        assertCounts(0);
    }

    @Test
    void rejectsOutOfRangeNonIntegerAndMalformedJsonRequests() throws Exception {
        for (String ttl : List.of("0", "-1", "25", "2147483648", "1.5", "1.0", "\"8\"", "true", "[]", "{}")) {
            ApiResponse response = post("invalid-ttl",
                    "{\"name\":\"Preview\",\"templateId\":\"sample-service\",\"ttlHours\":" + ttl + "}");
            assertEquals(400, response.status(), "Invalid ttlHours " + ttl + ": " + response.body());
            assertError(response, 400);
        }
        for (String body : List.of(
                "{\"name\":\"Preview\",\"templateId\":\"sample-service\",\"unexpected\":true}",
                "{\"name\":123,\"templateId\":\"sample-service\"}",
                "{\"name\":true,\"templateId\":\"sample-service\"}",
                "{\"name\":\"Preview\",\"templateId\":123}",
                "{", "[]", "null", "")) {
            assertError(post("invalid-json", body), 400);
        }
        assertCounts(0);
    }

    @Test
    void rejectsMissingOrInvalidIdempotencyKeys() throws Exception {
        assertError(post(null, payload("Preview", 8)), 400);
        for (String key : List.of("", "a".repeat(129), "_bad", "-bad", ".bad", ":bad",
                "bad key", "bad/key", "bad@key")) {
            assertError(post(key, payload("Preview", 8)), 400);
        }
        assertCounts(0);
    }

    @Test
    void unknownResourcesAndMalformedIdentifiersHaveStructuredErrors() throws Exception {
        for (String resource : List.of("environments", "operations")) {
            assertError(get("/api/v1/" + resource + "/" + UUID.randomUUID()), 404);
            assertError(get("/api/v1/" + resource + "/not-a-uuid"), 400);
        }
    }

    @Test
    void paginationDefaultsAndBoundsAreEnforced() throws Exception {
        ApiResponse empty = get("/api/v1/environments");
        assertEquals(200, empty.status());
        assertFields(empty.json(), "items", "page", "size", "totalElements", "totalPages");
        assertTrue(empty.json().path("items").isArray());
        assertEquals(0, empty.json().path("items").size());
        assertEquals(0, empty.json().path("page").asInt());
        assertEquals(20, empty.json().path("size").asInt());
        assertEquals(0, empty.json().path("totalElements").asLong());
        assertEquals(0, empty.json().path("totalPages").asInt());
        for (String query : List.of("page=0&size=1", "page=1000000&size=100")) {
            assertEquals(200, get("/api/v1/environments?" + query).status());
        }
        for (String query : List.of("page=-1", "page=1000001", "page=2147483648", "page=abc",
                "page=1.5", "size=0", "size=-1", "size=101", "size=abc", "size=1.5")) {
            assertError(get("/api/v1/environments?" + query), 400);
        }
    }

    @Test
    void paginationUsesCreatedAtThenUuidDescendingWithConsistentTotals() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            ApiResponse created = post("pagination-" + index, payload("Page " + index, 8));
            assertEquals(202, created.status());
            ids.add(created.json().path("environmentId").asText());
        }
        jdbc.update("UPDATE environments SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'");
        String newest = ids.get(0);
        jdbc.update("UPDATE environments SET created_at = TIMESTAMPTZ '2026-01-02 00:00:00+00' WHERE id = ?::uuid", newest);
        List<String> expected = jdbc.queryForList(
                "SELECT id::text FROM environments ORDER BY created_at DESC, id DESC", String.class);
        assertEquals(newest, expected.get(0));
        List<String> actual = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ApiResponse response = get("/api/v1/environments?page=" + page + "&size=2");
            assertEquals(200, response.status());
            assertEquals(page, response.json().path("page").asInt());
            assertEquals(2, response.json().path("size").asInt());
            assertEquals(5, response.json().path("totalElements").asLong());
            assertEquals(3, response.json().path("totalPages").asInt());
            assertEquals(page == 2 ? 1 : 2, response.json().path("items").size());
            for (JsonNode item : response.json().path("items")) {
                assertFields(item, "id", "name", "namespace", "templateId", "status",
                        "expiresAt", "createdAt", "updatedAt");
                actual.add(item.path("id").asText());
            }
        }
        assertEquals(expected, actual);
        assertEquals(0, get("/api/v1/environments?page=3&size=2").json().path("items").size());
    }

    @Test
    void concurrentIdenticalRequestsReserveExactlyOneCreation() throws Exception {
        List<ApiResponse> responses = concurrentPosts("parallel-same",
                java.util.Collections.nCopies(8, payload("Concurrent", 8)));
        JsonNode expected = responses.get(0).json();
        for (ApiResponse response : responses) {
            assertEquals(202, response.status(), response.body());
            assertEquals(expected, response.json());
        }
        assertCounts(1);
    }

    @Test
    void concurrentConflictingRequestsHaveOneWinnerAndOneConflict() throws Exception {
        List<ApiResponse> responses = concurrentPosts("parallel-conflict",
                List.of(payload("First", 8), payload("Second", 9)));
        assertEquals(List.of(202, 409), responses.stream().map(ApiResponse::status).sorted().toList());
        ApiResponse winner = responses.stream().filter(response -> response.status() == 202).findFirst().orElseThrow();
        ApiResponse loser = responses.stream().filter(response -> response.status() == 409).findFirst().orElseThrow();
        assertError(loser, 409);
        assertEquals(winner.json().path("environmentId").asText(),
                jdbc.queryForObject("SELECT id::text FROM environments", String.class));
        assertCounts(1);
    }

    @Test
    void failedOperationInsertRollsBackReservationAndEnvironment() throws Exception {
        assertAtomicRollback("operations");
    }

    @Test
    void failedAuditInsertRollsBackReservationEnvironmentAndOperation() throws Exception {
        assertAtomicRollback("audit_events");
    }

    @Test
    void requestIdsAreServerGeneratedReturnedAndLogged(CapturedOutput output) throws Exception {
        String supplied = "caller-controlled-request-id";
        ApiResponse invalid = request(port, "POST", "/api/v1/environments",
                payload("Preview", 8), Map.of("X-Request-Id", supplied));
        assertError(invalid, 400);
        String requestId = invalid.header("X-Request-Id");
        assertNotEquals(supplied, requestId);
        assertEquals(requestId, invalid.json().path("requestId").asText());
        assertTrue(output.getAll().contains(requestId), "The server-generated request ID must be logged");
        assertFalse(invalid.body().contains(supplied));
        ApiResponse valid = post("request-id", payload("Preview", 8));
        assertEquals(202, valid.status());
        assertDoesNotThrow(() -> UUID.fromString(valid.header("X-Request-Id")));
        assertNotEquals(requestId, valid.header("X-Request-Id"));
    }

    @Test
    void idempotencySurvivesStartingAFreshApplicationContext() throws Exception {
        ApiResponse original = post("restart-persistent", payload("Persistent", 8));
        assertEquals(202, original.status());

        // A separate server/context proves replay does not depend on the original application's memory.
        try (var restarted = new SpringApplicationBuilder(PlatformLabApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource." + "password" + "=" + POSTGRES.getPassword())) {
            int restartedPort = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            ApiResponse replay = request(restartedPort, "POST", "/api/v1/environments",
                    payload("Persistent", 8), Map.of("Idempotency-Key", "restart-persistent"));
            assertEquals(202, replay.status(), replay.body());
            assertEquals(original.json(), replay.json());
            assertEquals(original.header("Location"), replay.header("Location"));
            assertEquals(200, request(restartedPort, "GET",
                    original.header("Location"), null, Map.of()).status());
        }
        assertCounts(1);
    }

    private void assertAtomicRollback(String table) throws Exception {
        String constraint = "test_reject_create";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " CHECK (false) NOT VALID");
        try {
            ApiResponse failed = post("rollback-" + table, payload("Rollback", 8));
            assertError(failed, 500);
            String body = failed.body().toLowerCase(java.util.Locale.ROOT);
            for (String leaked : List.of(constraint, "postgres", "sqlstate", "jdbc", "psqlexception",
                    "insert into", "stacktrace", "local-development")) {
                assertFalse(body.contains(leaked), "Internal database details leaked: " + failed.body());
            }
            assertCounts(0);
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
        }
        assertEquals(202, post("rollback-" + table, payload("Rollback", 8)).status(),
                "A rolled-back reservation must not prevent retry");
        assertCounts(1);
    }

    private List<ApiResponse> concurrentPosts(String key, List<String> bodies) throws Exception {
        var executor = Executors.newFixedThreadPool(bodies.size());
        CyclicBarrier barrier = new CyclicBarrier(bodies.size());
        try {
            var tasks = bodies.stream().<java.util.concurrent.Callable<ApiResponse>>map(body -> () -> {
                barrier.await(20, TimeUnit.SECONDS);
                return post(key, body);
            }).toList();
            List<ApiResponse> responses = new ArrayList<>();
            for (var future : executor.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertCounts(int expected) {
        for (String table : List.of("environments", "operations", "idempotency_requests", "audit_events")) {
            assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    private void assertError(ApiResponse response, int status) {
        assertEquals(status, response.status(), response.body());
        assertFields(response.json(), "code", "message", "requestId", "fieldErrors");
        assertFalse(response.json().path("code").asText().isBlank());
        assertFalse(response.json().path("message").asText().isBlank());
        assertDoesNotThrow(() -> UUID.fromString(response.header("X-Request-Id")));
        assertEquals(response.header("X-Request-Id"), response.json().path("requestId").asText());
        assertTrue(response.json().path("fieldErrors").isArray());
        for (JsonNode error : response.json().path("fieldErrors")) {
            assertFields(error, "field", "message");
            assertFalse(error.path("field").asText().isBlank());
            assertFalse(error.path("message").asText().isBlank());
        }
    }

    private void assertFieldError(ApiResponse response, String field) {
        for (JsonNode error : response.json().path("fieldErrors")) {
            if (field.equals(error.path("field").asText())) {
                return;
            }
        }
        fail("Expected field error for " + field + ": " + response.body());
    }

    private static void assertFields(JsonNode node, String... fields) {
        Set<String> actual = new HashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        assertEquals(Set.of(fields), actual);
    }

    private static String uuid(JsonNode node, String field) {
        String value = node.path(field).asText();
        assertDoesNotThrow(() -> UUID.fromString(value));
        return value;
    }

    private static Instant timestamp(JsonNode node, String field) {
        return Instant.parse(node.path(field).asText());
    }

    private static void assertBetween(Instant actual, Instant lower, Instant upper) {
        assertFalse(actual.isBefore(lower), actual + " is before " + lower);
        assertFalse(actual.isAfter(upper), actual + " is after " + upper);
    }

    private String payload(String name, int ttl) {
        return mapper.writeValueAsString(Map.of("name", name, "templateId", "sample-service", "ttlHours", ttl));
    }

    private ApiResponse post(String key, String body) throws Exception {
        return request(port, "POST", "/api/v1/environments", body,
                key == null ? Map.of() : Map.of("Idempotency-Key", key));
    }

    private ApiResponse get(String path) throws Exception {
        return request(port, "GET", path, null, Map.of());
    }

    private ApiResponse request(int targetPort, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(30));
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), mapper.readTree(response.body()), response.body(), response.headers());
    }

    private record ApiResponse(int status, JsonNode json, String body, java.net.http.HttpHeaders headers) {
        String header(String name) {
            return headers.firstValue(name).orElse("");
        }
    }
}
