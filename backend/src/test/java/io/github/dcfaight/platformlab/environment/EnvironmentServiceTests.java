package io.github.dcfaight.platformlab.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.dcfaight.platformlab.audit.AuditRepository;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import io.github.dcfaight.platformlab.operation.OperationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import io.github.dcfaight.platformlab.config.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class EnvironmentServiceTests {
    @Test
    void fixedClockDeterminesDefaultExpiryAndEveryCreationTimestamp() {
        assertCreationTimestamps(null, 8);
    }

    @Test
    void fixedClockDeterminesExplicitMaximumTtlExpiry() {
        assertCreationTimestamps(24, 24);
    }

    @Test
    void deletionRefusesNoncanonicalPersistedNamespaceBeforeWriting() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        when(environments.lock(id)).thenReturn(Optional.of(new EnvironmentRecord(id, "bad", "default",
                "sample-service", "READY", now.plusSeconds(3600), now, now)));
        var service = new EnvironmentService(environments, operations, idempotency, audit, Clock.systemUTC());
        assertThrows(ApiException.class, () -> service.delete(id, "alice"));
        verifyNoInteractions(operations, audit, idempotency);
    }

    @Test
    void expiryGuardAcceptsServerClockBoundaryButRejectsFutureNanosecond() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-16T12:34:56.123456Z");
        when(environments.lock(id)).thenReturn(Optional.of(new EnvironmentRecord(id, "Expired",
                "env-" + id.toString().replace("-", ""), "sample-service", "READY", now,
                now.minusSeconds(3600), now)));
        var service = new EnvironmentService(environments, operations, idempotency, audit,
                Clock.fixed(now, ZoneId.of("Asia/Tokyo")));

        var future = assertThrows(ApiException.class, () -> service.delete(id, now.plusNanos(1), "cleanup-bot"));
        assertEquals(HttpStatus.BAD_REQUEST, future.status());
        assertEquals("INVALID_REQUEST", future.code());
        verifyNoInteractions(environments, operations, audit, idempotency);
        var accepted = service.delete(id, now, "cleanup-bot");
        assertEquals("DELETING", accepted.environmentStatus());
        org.mockito.Mockito.verify(audit).recordDeletion(eq(id), eq(accepted.operationId()), eq("cleanup-bot"), any());
    }

    @Test
    void mutationsRequireAnAuthenticatedActorBeforeAnyWrite() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        var service = new EnvironmentService(environments, operations, idempotency, audit, Clock.systemUTC());
        var request = new CreateEnvironmentRequest("No actor", "sample-service", 1);
        for (String actor : new String[] {null, "", "  "}) {
            assertThrows(IllegalArgumentException.class, () -> service.create("no-actor", request, actor));
            assertThrows(IllegalArgumentException.class, () -> service.delete(UUID.randomUUID(), actor));
        }
        verifyNoInteractions(environments, operations, idempotency, audit);
    }

    private void assertCreationTimestamps(Integer suppliedTtl, int expectedTtl) {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        Instant fixedInstant = Instant.parse("2026-10-16T12:34:56.123456789Z");
        Clock clock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Tokyo"));
        Instant persistedInstant = fixedInstant.truncatedTo(ChronoUnit.MICROS);
        var request = new CreateEnvironmentRequest("  Clock preview  ", "sample-service", suppliedTtl);
        when(idempotency.reserve(eq("fixed-clock"), eq(request), any(), any())).thenReturn(true);
        var service = new EnvironmentService(environments, operations, idempotency, audit, clock);

        AcceptedEnvironmentResponse response = service.create("fixed-clock", request, "alice");

        var recordCaptor = ArgumentCaptor.forClass(EnvironmentRecord.class);
        var ordered = inOrder(idempotency, environments, operations, audit);
        ordered.verify(idempotency).reserve("fixed-clock", request, response.environmentId(), response.operationId());
        ordered.verify(environments).insert(recordCaptor.capture());
        ordered.verify(operations).insert(response.operationId(), response.environmentId(), persistedInstant);
        ordered.verify(audit).recordCreation(response.environmentId(), response.operationId(), "alice",
                persistedInstant);
        ordered.verifyNoMoreInteractions();
        EnvironmentRecord environment = recordCaptor.getValue();
        assertEquals(response.environmentId(), environment.id());
        assertEquals("Clock preview", environment.name());
        assertEquals("sample-service", environment.templateId());
        assertEquals("env-" + response.environmentId().toString().replace("-", ""), environment.namespace());
        assertEquals("REQUESTED", environment.status());
        assertEquals(persistedInstant, environment.createdAt());
        assertEquals(persistedInstant, environment.updatedAt());
        assertEquals(persistedInstant.plus(Duration.ofHours(expectedTtl)), environment.expiresAt());
        assertEquals("REQUESTED", response.environmentStatus());
        assertEquals("PENDING", response.operationStatus());
    }
}
