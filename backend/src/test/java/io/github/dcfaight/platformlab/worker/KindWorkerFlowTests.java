package io.github.dcfaight.platformlab.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_KIND_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.worker.enabled=true",
        "platform.worker.poll-delay-ms=100",
        "platform.worker.readiness-poll-interval=1s"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class KindWorkerFlowTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void acceptedHttpRequestRunsThroughScheduledWorkerToRealKindReadiness() throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var accepted = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "kind-worker-full-flow")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Kind worker flow","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        System.out.println("KIND_HTTP_ACCEPTED " + accepted.body());
        JsonNode acceptance = mapper.readTree(accepted.body());
        String operationId = acceptance.path("operationId").asText();
        String environmentId = acceptance.path("environmentId").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        JsonNode status;
        do {
            status = get(client, "/api/v1/operations/" + operationId);
            if ("SUCCEEDED".equals(status.path("status").asText())
                    || "FAILED".equals(status.path("status").asText())) {
                break;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        System.out.println("KIND_HTTP_OPERATION " + status);
        assertEquals("SUCCEEDED", status.path("status").asText(), status::toString);
        assertEquals(accepted.headers().firstValue("X-Request-Id").orElseThrow(),
                status.path("correlationId").asText());
        assertEquals(1, status.path("attemptCount").asInt());
        assertEquals("READY", status.path("progressStep").asText());
        assertFalse(status.path("completedAt").isNull());
        assertEquals("CLAIMED", status.path("events").get(0).path("type").asText());
        assertEquals("SUCCEEDED", status.path("events").get(status.path("events").size() - 1)
                .path("type").asText());

        JsonNode environment = get(client, "/api/v1/environments/" + environmentId);
        System.out.println("KIND_HTTP_ENVIRONMENT " + environment);
        assertEquals("READY", environment.path("status").asText());
        assertEquals("env-" + environmentId.replace("-", ""), environment.path("namespace").asText());
        // Keep the managed namespace available for inspection, as in the provisioner integration test.
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
