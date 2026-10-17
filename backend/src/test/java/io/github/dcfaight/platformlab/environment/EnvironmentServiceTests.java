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
import io.github.dcfaight.platformlab.security.TeamAuthorization;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;

class EnvironmentServiceTests {
    private static final Authentication OPERATOR = new TestingAuthenticationToken("alice", null, "ROLE_OPERATOR");
    private static final Authentication CLEANUP = new TestingAuthenticationToken("cleanup-bot", null, "ROLE_CLEANUP");

    private TeamAuthorization authorization() {
        return new TeamAuthorization(mock(JdbcTemplate.class));
    }

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
        var service = new EnvironmentService(environments, operations, idempotency, audit, Clock.systemUTC(), authorization());
        assertThrows(ApiException.class, () -> service.delete(id, OPERATOR));
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
                Clock.fixed(now, ZoneId.of("Asia/Tokyo")), authorization());

        var future = assertThrows(ApiException.class, () -> service.delete(id, now.plusNanos(1), CLEANUP));
        assertEquals(HttpStatus.BAD_REQUEST, future.status());
        assertEquals("INVALID_REQUEST", future.code());
        verifyNoInteractions(environments, operations, audit, idempotency);
        var accepted = service.delete(id, now, CLEANUP);
        assertEquals("DELETING", accepted.environmentStatus());
        org.mockito.Mockito.verify(audit).recordDeletion(eq(id), eq(accepted.operationId()), eq("cleanup-bot"), any());
    }

    @Test
    void mutationsRequireAnAuthenticatedActorBeforeAnyWrite() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        var service = new EnvironmentService(environments, operations, idempotency, audit, Clock.systemUTC(), authorization());
        var request = new CreateEnvironmentRequest("No actor", "sample-service", 1);
        for (Authentication actor : new Authentication[] {null,
                new TestingAuthenticationToken("alice", null),
                new TestingAuthenticationToken("unknown", null, "ROLE_UNKNOWN")}) {
            assertThrows(ApiException.class, () -> service.create("no-actor", request, actor));
            assertThrows(ApiException.class, () -> service.delete(UUID.randomUUID(), actor));
            assertThrows(ApiException.class, () -> service.list(0, 10, actor));
        }
        verifyNoInteractions(environments, operations, idempotency, audit);
    }

    @Test
    void deniedCreationAndReplayNeverConsultOrReserveIdempotencyKeys() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        var service = new EnvironmentService(environments, operations, idempotency, audit,
                Clock.systemUTC(), authorization());
        var developer = new TestingAuthenticationToken("revoked", null, "ROLE_DEVELOPER");
        for (String key : new String[] {"fresh-key", "existing-key"}) {
            for (var actor : new Authentication[] {developer, CLEANUP}) {
                for (var request : new CreateEnvironmentRequest[] {
                        new CreateEnvironmentRequest("Denied", "sample-service", 1),
                        new CreateEnvironmentRequest("Denied", "sample-service", 1, "team-a")}) {
                    var failure = assertThrows(ApiException.class, () -> service.create(key, request, actor));
                    assertEquals(HttpStatus.FORBIDDEN, failure.status());
                }
            }
        }
        verifyNoInteractions(environments, operations, idempotency, audit);
    }

    @Test
    void teamAuthorizationPrecedesReservationAndPersistsTrustedOwnership() {
        var environments = mock(EnvironmentRepository.class);
        var operations = mock(OperationRepository.class);
        var idempotency = mock(IdempotencyRepository.class);
        var audit = mock(AuditRepository.class);
        var authorization = mock(TeamAuthorization.class);
        var developer = new TestingAuthenticationToken("developer", null, "ROLE_DEVELOPER");
        var request = new CreateEnvironmentRequest("Team preview", "sample-service", 1, "team-a");
        when(authorization.createTeam(developer, "team-a")).thenReturn("team-a");
        when(idempotency.reserve(eq("developer:team-a"), eq("team-key"), eq(request), any(), any())).thenReturn(true);
        var service = new EnvironmentService(environments, operations, idempotency, audit,
                Clock.systemUTC(), authorization);
        var response = service.create("team-key", request, developer);
        var ordered = inOrder(authorization, idempotency, environments, operations, audit);
        ordered.verify(authorization).createTeam(developer, "team-a");
        ordered.verify(idempotency).reserve("developer:team-a", "team-key", request,
                response.environmentId(), response.operationId());
        var record = ArgumentCaptor.forClass(EnvironmentRecord.class);
        ordered.verify(environments).insert(record.capture());
        assertEquals("team-a", record.getValue().teamId());
        assertEquals("developer", record.getValue().createdBy());
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
        when(idempotency.reserve(eq("alice:"), eq("fixed-clock"), eq(request), any(), any())).thenReturn(true);
        var service = new EnvironmentService(environments, operations, idempotency, audit, clock, authorization());

        AcceptedEnvironmentResponse response = service.create("fixed-clock", request, OPERATOR);

        var recordCaptor = ArgumentCaptor.forClass(EnvironmentRecord.class);
        var ordered = inOrder(idempotency, environments, operations, audit);
        ordered.verify(idempotency).find("", "fixed-clock");
        ordered.verify(idempotency).reserve("alice:", "fixed-clock", request, response.environmentId(), response.operationId());
        ordered.verify(environments).insert(recordCaptor.capture());
        ordered.verify(operations).insert(response.operationId(), response.environmentId(), persistedInstant);
        ordered.verify(audit).recordCreation(response.environmentId(), response.operationId(), "alice",
                persistedInstant);
        ordered.verifyNoMoreInteractions();
        EnvironmentRecord environment = recordCaptor.getValue();
        assertEquals(response.environmentId(), environment.id());
        assertEquals("Clock preview", environment.name());
        assertEquals("sample-service", environment.templateId());
        assertEquals("alice", environment.createdBy());
        assertEquals(null, environment.teamId());
        assertEquals("env-" + response.environmentId().toString().replace("-", ""), environment.namespace());
        assertEquals("REQUESTED", environment.status());
        assertEquals(persistedInstant, environment.createdAt());
        assertEquals(persistedInstant, environment.updatedAt());
        assertEquals(persistedInstant.plus(Duration.ofHours(expectedTtl)), environment.expiresAt());
        assertEquals("REQUESTED", response.environmentStatus());
        assertEquals("PENDING", response.operationStatus());
    }
}
