package io.github.dcfaight.platformlab.operation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dcfaight.platformlab.security.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "platform.worker.enabled=false")
@Testcontainers
class OperationMetricsTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestUsers.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OperationMetricsRepository metrics;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE environments CASCADE");
    }

    @Test
    void emptyDatabaseHasFixedZeroCountsAndTimingPopulations() throws Exception {
        var result = get();
        assertEquals(mapper.readTree("""
                {
                  "counts":{"PENDING":0,"RUNNING":0,"SUCCEEDED":0,"FAILED":0},
                  "completedDuration":{"count":0,"averageMillis":0.0,"maxMillis":0.0},
                  "initialQueueDelay":{"count":0,"averageMillis":0.0,"maxMillis":0.0},
                  "worker":{"localWorkerEnabled":false,"expiredRunningLeases":0,"oldestPendingAgeMillis":0.0}
                }
                """), result);
    }

    @Test
    void aggregatesDurableStatusesTimingsAndLeaseSignalsWithoutMutatingRows() throws Exception {
        Instant now = Instant.now();
        Instant created = now.minusSeconds(60);
        Instant oldest = now.minusSeconds(120);
        UUID pending = seed("PENDING", oldest, null, null, now.minusSeconds(1));
        seed("PENDING", created, created.plusSeconds(1), null, null);
        seed("RUNNING", created, created.plusSeconds(2), null, now.minusSeconds(1));
        seed("RUNNING", created, created.plusSeconds(3), null, now.plusSeconds(3600));
        seed("RUNNING", created, created.plusSeconds(4), null, null);
        seed("SUCCEEDED", created, created.plusSeconds(5), created.plusSeconds(15), now.minusSeconds(1));
        seed("FAILED", created, created.plusSeconds(6), created.plusSeconds(36), null);

        var result = get();
        assertEquals(mapper.readTree("""
                {"PENDING":2,"RUNNING":3,"SUCCEEDED":1,"FAILED":1}
                """), result.path("counts"));
        assertTiming(result.path("completedDuration"), 2, 20000, 30000);
        assertTiming(result.path("initialQueueDelay"), 6, 3500, 6000);
        assertEquals(1, result.path("worker").path("expiredRunningLeases").asLong());
        assertFalse(result.path("worker").path("localWorkerEnabled").asBoolean());
        double age = result.path("worker").path("oldestPendingAgeMillis").asDouble();
        assertTrue(age >= 120000);
        assertTrue(age <= Duration.between(oldest, Instant.now()).toMillis() + 1000);
        assertEquals(7L, jdbc.queryForObject("SELECT count(*) FROM operations", Long.class));
        assertEquals(result.path("counts"), get().path("counts"));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM operations WHERE id = ? AND status = 'PENDING'", Long.class, pending));

        jdbc.update("UPDATE operations SET status = 'FAILED' WHERE id = ?", pending);
        var changed = get();
        assertEquals(1, changed.path("counts").path("PENDING").asLong());
        assertEquals(2, changed.path("counts").path("FAILED").asLong());
        assertTiming(changed.path("completedDuration"), 2, 20000, 30000);
    }

    @Test
    void missingTimestampsAreExcludedAndInvertedTimesAndFuturePendingAgeAreClamped() throws Exception {
        Instant created = Instant.now().plusSeconds(3600);
        seed("PENDING", created, null, null, null);
        seed("SUCCEEDED", created, null, created.plusSeconds(10), null);
        seed("FAILED", created, created.plusSeconds(1), null, null);
        seed("FAILED", created, created.minusSeconds(1), created.minusSeconds(2), null);
        // Nonterminal timestamps must not enter the completed duration population.
        seed("RUNNING", created, created.plusSeconds(2), created.plusSeconds(100), null);

        var result = get();
        assertTiming(result.path("completedDuration"), 1, 0, 0);
        assertTiming(result.path("initialQueueDelay"), 3, 1000, 2000);
        assertEquals(0, result.path("worker").path("oldestPendingAgeMillis").asDouble());
        assertEquals(0, result.path("worker").path("expiredRunningLeases").asLong());
    }

    @Test
    void workerFlagReflectsOnlyLocalConfigurationNotPresenceOfLeases() {
        assertTrue(metrics.snapshot(true).worker().localWorkerEnabled());
        assertFalse(metrics.snapshot(false).worker().localWorkerEnabled());
        assertEquals(0, metrics.snapshot(true).worker().expiredRunningLeases());
    }

    private void assertTiming(JsonNode timing, long count, double average, double maximum) {
        assertEquals(count, timing.path("count").asLong());
        assertEquals(average, timing.path("averageMillis").asDouble(), 0.001);
        assertEquals(maximum, timing.path("maxMillis").asDouble(), 0.001);
    }

    private UUID seed(String status, Instant created, Instant started, Instant completed, Instant lease) {
        UUID environmentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO environments (id, name, namespace, template_id, status,
                    expires_at, created_at, updated_at)
                VALUES (?, 'Metrics fixture', ?, 'sample-service', 'REQUESTED', ?, ?, ?)
                """, environmentId, "env-" + environmentId.toString().replace("-", ""),
                timestamp(created.plusSeconds(7200)), timestamp(created), timestamp(created));
        jdbc.update("""
                INSERT INTO operations (id, environment_id, type, status, next_attempt_at,
                    created_at, updated_at, started_at, completed_at, lease_expires_at)
                VALUES (?, ?, 'CREATE_ENVIRONMENT', ?, ?, ?, ?, ?, ?, ?)
                """, operationId, environmentId, status, timestamp(created), timestamp(created),
                timestamp(created), timestamp(started), timestamp(completed), timestamp(lease));
        return operationId;
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private JsonNode get() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/operations/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
}
