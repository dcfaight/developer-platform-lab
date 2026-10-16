package io.github.dcfaight.platformlab.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.environment.EnvironmentService;
import io.github.dcfaight.platformlab.environment.CreateEnvironmentRequest;
import io.github.dcfaight.platformlab.kubernetes.EnvironmentProvisioner;
import io.github.dcfaight.platformlab.kubernetes.ProvisioningException;
import io.github.dcfaight.platformlab.operation.OperationRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class WorkerTests {
    @Container
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerRepository repository;
    @Autowired OperationRepository operations;
    @Autowired EnvironmentRepository environments;
    @Autowired EnvironmentService service;
    private EnvironmentProvisioner provisioner;
    private ProvisioningWorker worker;
    private final WorkerProperties properties = new WorkerProperties(Duration.ofSeconds(30), 3,
            Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMinutes(5));

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE audit_events, idempotency_requests, operation_events, operations, environments CASCADE");
        provisioner = mock(EnvironmentProvisioner.class);
        worker = new ProvisioningWorker(repository, environments, provisioner, properties);
    }

    @Test
    void simultaneousClaimersOnlyOneOwnsOperation() throws Exception {
        UUID id = create();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var tasks = List.of(
                    pool.submit(() -> { start.await(); return repository.claim(properties); }),
                    pool.submit(() -> { start.await(); return repository.claim(properties); }));
            var first = tasks.get(0).get(10, TimeUnit.SECONDS);
            var second = tasks.get(1).get(10, TimeUnit.SECONDS);
            assertNotEquals(first.isPresent(), second.isPresent());
            assertEquals(id, first.orElseGet(second::orElseThrow).id());
            assertEquals(1, operations.find(id).orElseThrow().attemptCount());
            assertEquals(List.of("CLAIMED"), eventTypes(id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredOwnerCannotWriteEvenBeforeLeaseIsReclaimed() {
        UUID id = create();
        var stale = repository.claim(properties).orElseThrow();
        expire(id);
        assertFalse(repository.provisioned(stale));
        assertFalse(repository.waiting(stale, Duration.ZERO));
        assertFalse(repository.retry(stale, "TRANSIENT", "Temporary failure.", Duration.ZERO));
        assertFalse(repository.fail(stale, "FAILED", "Failure."));
        assertFalse(repository.succeed(stale));
        assertEquals("PROVISIONING", environmentStatus(id));
        assertEquals(List.of("CLAIMED"), eventTypes(id));

        var recovered = repository.claim(properties).orElseThrow();
        assertNotEquals(stale.leaseToken(), recovered.leaseToken());
        assertEquals(2, operations.find(id).orElseThrow().attemptCount());
        assertFalse(repository.succeed(stale));
        assertTrue(repository.succeed(recovered));
        assertEquals("READY", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "CLAIMED", "SUCCEEDED"), eventTypes(id));
    }

    @Test
    void successfulReadinessCompletesAndEventsAreDurable() {
        UUID id = create();
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("READY", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROVISIONED", "SUCCEEDED"), eventTypes(id));
        assertTrue(operations.events(id).stream().allMatch(event -> event.createdAt() != null));
        worker.poll();
        verify(provisioner, times(1)).provision(any());
    }

    @Test
    void deletingOperationCascadesDurableEvents() {
        UUID id = create();
        worker.poll();
        assertFalse(operations.events(id).isEmpty());
        jdbc.update("DELETE FROM audit_events WHERE operation_id = ?", id);
        jdbc.update("DELETE FROM idempotency_requests WHERE operation_id = ?", id);
        jdbc.update("DELETE FROM operations WHERE id = ?", id);
        assertTrue(operations.events(id).isEmpty());
    }

    @Test
    void waitingDoesNotMarkReadyOrBurnProvisioningAttemptsAndSurvivesRestart() {
        UUID id = create();
        worker.poll();
        assertEquals("PENDING", operations.find(id).orElseThrow().status());
        assertEquals("WAITING", operations.find(id).orElseThrow().progressStep());
        assertEquals("PROVISIONING", environmentStatus(id));
        worker = new ProvisioningWorker(new WorkerRepository(jdbc), environments, provisioner, properties);
        for (int i = 0; i < 6; i++) {
            due(id);
            worker.poll();
        }
        assertEquals(1, operations.find(id).orElseThrow().attemptCount());
        verify(provisioner, times(1)).provision(any());
        when(provisioner.isReady(any())).thenReturn(true);
        due(id);
        worker.poll();
        assertEquals("READY", environmentStatus(id));
    }

    @Test
    void neverReadyFailsAtDurableDeadline() {
        UUID id = create();
        worker.poll();
        jdbc.update("UPDATE operations SET started_at = clock_timestamp() - interval '6 minutes' WHERE id = ?", id);
        due(id);
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("READINESS_TIMEOUT", operations.find(id).orElseThrow().failureCode());
        assertEquals("FAILED", environmentStatus(id));
        assertEquals("FAILED", eventTypes(id).get(eventTypes(id).size() - 1));
    }

    @Test
    void transientFailuresUseDatabaseBackoffAndAreBounded() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(true);
        when(failure.code()).thenReturn("KUBERNETES_UNAVAILABLE");
        when(failure.getMessage()).thenReturn("Kubernetes is unavailable.");
        doThrow(failure).when(provisioner).provision(any());
        worker.poll();
        assertEquals("PENDING", operations.find(id).orElseThrow().status());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT next_attempt_at > updated_at FROM operations WHERE id = ?
                """, Boolean.class, id));
        for (int i = 0; i < 2; i++) {
            due(id);
            worker.poll();
        }
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(3, operations.find(id).orElseThrow().attemptCount());
        assertEquals("KUBERNETES_UNAVAILABLE", operations.find(id).orElseThrow().failureCode());
        assertEquals(List.of("CLAIMED", "RETRY", "CLAIMED", "RETRY", "CLAIMED", "FAILED"), eventTypes(id));
    }

    @Test
    void permanentFailureDoesNotRetry() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(false);
        when(failure.code()).thenReturn("KUBERNETES_FORBIDDEN");
        when(failure.getMessage()).thenReturn("Kubernetes access was denied.");
        doThrow(failure).when(provisioner).provision(any());
        worker.poll();
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals("FAILED", environmentStatus(id));
        assertTrue(repository.claim(properties).isEmpty());
        assertEquals(List.of("CLAIMED", "FAILED"), eventTypes(id));
    }

    @Test
    void readinessTransientErrorsAreBoundedWithoutReprovisioning() {
        UUID id = create();
        var failure = mock(ProvisioningException.class);
        when(failure.retryable()).thenReturn(true);
        when(failure.code()).thenReturn("KUBERNETES_UNAVAILABLE");
        when(failure.getMessage()).thenReturn("Kubernetes is unavailable.");
        when(provisioner.isReady(any())).thenThrow(failure);
        for (int i = 0; i < properties.maxAttempts(); i++) {
            due(id);
            worker.poll();
        }
        assertEquals("FAILED", operations.find(id).orElseThrow().status());
        assertEquals(1, operations.find(id).orElseThrow().attemptCount());
        verify(provisioner, times(1)).provision(any());
        verify(provisioner, times(3)).isReady(any());
    }

    @Test
    void leaseExpiringDuringHttpCannotCompleteOrAppendEvents() {
        UUID id = create();
        when(provisioner.isReady(any())).thenAnswer(invocation -> {
            expire(id);
            return true;
        });
        worker.poll();
        assertEquals("RUNNING", operations.find(id).orElseThrow().status());
        assertEquals("PROVISIONING", environmentStatus(id));
        assertEquals(List.of("CLAIMED", "PROVISIONED"), eventTypes(id));
        when(provisioner.isReady(any())).thenReturn(true);
        worker.poll();
        assertEquals("SUCCEEDED", operations.find(id).orElseThrow().status());
        assertEquals("READY", environmentStatus(id));
        verify(provisioner, times(1)).provision(any());
    }

    @Test
    void crashOnLastAttemptFailsAtomicallyAfterRestart() {
        UUID id = create();
        for (int i = 0; i < properties.maxAttempts(); i++) {
            assertEquals("RUNNING", repository.claim(properties).orElseThrow().status());
            expire(id);
        }
        var restart = new WorkerRepository(jdbc);
        assertEquals("FAILED", restart.claim(properties).orElseThrow().status());
        assertEquals(3, operations.find(id).orElseThrow().attemptCount());
        assertEquals("ATTEMPTS_EXHAUSTED", operations.find(id).orElseThrow().failureCode());
        assertEquals("FAILED", environmentStatus(id));
        assertTrue(restart.claim(properties).isEmpty());
    }

    @Test
    void unexpectedExceptionIsSanitizedPersistedAndNotSwallowed() {
        UUID id = create();
        doThrow(new IllegalStateException("secret-token-response-body")).when(provisioner).provision(any());
        var thrown = assertThrows(IllegalStateException.class, worker::poll);
        assertFalse(thrown.getMessage().contains("secret"));
        assertNull(thrown.getCause());
        var operation = operations.find(id).orElseThrow();
        assertEquals("FAILED", operation.status());
        assertEquals("INTERNAL_ERROR", operation.failureCode());
        assertEquals("An internal provisioning error occurred.", operation.failureMessage());
        assertEquals(List.of("CLAIMED", "FAILED"), eventTypes(id));
    }

    private UUID create() {
        return service.create(UUID.randomUUID().toString(),
                new CreateEnvironmentRequest("Worker test", "sample-service", 1)).operationId();
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE operations SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private void due(UUID id) {
        jdbc.update("UPDATE operations SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private String environmentStatus(UUID id) {
        return environments.find(operations.find(id).orElseThrow().environmentId()).orElseThrow().status();
    }

    private List<String> eventTypes(UUID id) {
        return operations.events(id).stream().map(OperationRepository.OperationEvent::type).toList();
    }
}
