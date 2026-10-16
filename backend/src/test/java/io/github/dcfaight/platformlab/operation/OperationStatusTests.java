package io.github.dcfaight.platformlab.operation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dcfaight.platformlab.worker.WorkerProperties;
import io.github.dcfaight.platformlab.worker.WorkerRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OperationStatusTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired WorkerRepository worker;
    @Autowired ObjectMapper mapper;

    @Test
    void statusContainsDurableFailureProgressEventsAndOriginalRequestCorrelation() throws Exception {
        var client = HttpClient.newHttpClient();
        var accepted = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "rich-status")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Rich status","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        String correlation = accepted.headers().firstValue("X-Request-Id").orElseThrow();
        UUID id = UUID.fromString(mapper.readTree(accepted.body()).path("operationId").asText());
        var claim = worker.claim(new WorkerProperties(Duration.ofSeconds(30), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofMinutes(5))).orElseThrow();
        assertEquals(id, claim.id());
        worker.provisioned(claim);
        worker.fail(claim, "KUBERNETES_FORBIDDEN", "Kubernetes access was denied.");

        var response = client.send(HttpRequest.newBuilder(url("/api/v1/operations/" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var status = mapper.readTree(response.body());
        assertEquals("FAILED", status.path("status").asText());
        assertEquals("FAILED", status.path("progressStep").asText());
        assertEquals("KUBERNETES_FORBIDDEN", status.path("failureCode").asText());
        assertEquals("Kubernetes access was denied.", status.path("failureMessage").asText());
        assertEquals(correlation, status.path("correlationId").asText());
        assertNotEquals(correlation, response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(1, status.path("attemptCount").asInt());
        assertFalse(status.path("startedAt").isNull());
        assertFalse(status.path("completedAt").isNull());
        assertTrue(status.path("leaseExpiresAt").isNull());
        assertEquals(3, status.path("events").size());
        assertEquals("CLAIMED", status.path("events").get(0).path("type").asText());
        assertEquals("PROVISIONED", status.path("events").get(1).path("type").asText());
        assertEquals("FAILED", status.path("events").get(2).path("type").asText());
        for (var event : status.path("events")) {
            assertEquals(correlation, event.path("correlationId").asText());
            assertFalse(event.path("code").asText().isEmpty());
            assertFalse(event.path("message").asText().isEmpty());
            assertFalse(event.path("createdAt").asText().isEmpty());
        }
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
