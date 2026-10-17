package io.github.dcfaight.platformlab.environment;

import io.github.dcfaight.platformlab.audit.AuditRepository;
import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import io.github.dcfaight.platformlab.operation.OperationRepository;
import io.github.dcfaight.platformlab.operation.OperationPage;
import io.github.dcfaight.platformlab.security.TeamAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final EnvironmentRepository environments;
    private final OperationRepository operations;
    private final IdempotencyRepository idempotency;
    private final AuditRepository audit;
    private final Clock clock;
    private final TeamAuthorization authorization;

    public EnvironmentService(EnvironmentRepository environments, OperationRepository operations,
            IdempotencyRepository idempotency, AuditRepository audit, Clock clock, TeamAuthorization authorization) {
        this.environments = environments;
        this.operations = operations;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.authorization = authorization;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AcceptedEnvironmentResponse create(String key, CreateEnvironmentRequest request, Authentication authentication) {
        String team = authorization.createTeam(authentication, request.teamId());
        String actor = authentication.getName();
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Idempotency-Key must be 1 to 128 characters: letters, digits, '.', '_', ':', '-' and start with a letter or digit.");
        }
        UUID environmentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String scope = actor + ":" + (team == null ? "" : team);
        if (team == null && authorization.hasRole(authentication, "OPERATOR")) {
            var legacy = idempotency.find("", key);
            if (legacy.isPresent()) {
                return replay(legacy.orElseThrow(), request, authentication);
            }
        }
        if (!idempotency.reserve(scope, key, request, environmentId, operationId)) {
            return replay(idempotency.find(scope, key).orElseThrow(), request, authentication);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        environments.insert(new EnvironmentRecord(environmentId, request.name(),
                "env-" + environmentId.toString().replace("-", ""), request.templateId(), "REQUESTED",
                now.plus(request.ttlHours(), ChronoUnit.HOURS), now, now, team, actor));
        operations.insert(operationId, environmentId, now);
        audit.recordCreation(environmentId, operationId, actor, now);
        return accepted(environmentId, operationId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EnvironmentPage list(int page, int size, Authentication authentication) {
        authorization.requireAccount(authentication);
        validatePage(page, size);
        boolean global = authorization.hasRole(authentication, "OPERATOR") || authorization.hasRole(authentication, "CLEANUP");
        long total = global ? environments.count() : environments.countForAccount(authentication.getName());
        var records = global ? environments.page(page, size)
                : environments.pageForAccount(authentication.getName(), page, size);
        return new EnvironmentPage(records.stream().map(this::response).toList(),
                page, size, total, (total + size - 1) / size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AcceptedEnvironmentResponse delete(UUID id, Authentication authentication) {
        return delete(id, null, authentication);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AcceptedEnvironmentResponse delete(UUID id, Instant expiredBefore, Authentication authentication) {
        authorization.requireAccount(authentication);
        if (expiredBefore != null && expiredBefore.isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "expiredBefore must not be in the future.");
        }
        var environment = environments.lock(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found."));
        authorization.delete(authentication, environment, expiredBefore);
        if (!("env-" + id.toString().replace("-", "")).equals(environment.namespace())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ENVIRONMENT_NAMESPACE",
                    "The persisted environment namespace is invalid.");
        }
        if ("DELETED".equals(environment.status()) || "REQUESTED".equals(environment.status())
                || "PROVISIONING".equals(environment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_STATE_CONFLICT",
                    "The environment cannot be deleted in its current state.");
        }
        var active = operations.active(id);
        // A replay must pass the expiry guard too, before returning its accepted response.
        if (expiredBefore != null && (environment.expiresAt().isAfter(expiredBefore)
                || !java.util.Set.of("READY", "FAILED", "DELETING").contains(environment.status()))) {
            throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_STATE_CONFLICT",
                    "The environment is not eligible for deletion at the requested expiry cutoff.");
        }
        if (active.isPresent()) {
            var operation = active.orElseThrow();
            if ("DELETE_ENVIRONMENT".equals(operation.type())) {
                return new AcceptedEnvironmentResponse(id, operation.id(), "DELETING", operation.status());
            }
            throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_STATE_CONFLICT",
                    "Another operation is active for this environment.");
        }
        if (!java.util.Set.of("READY", "FAILED", "DELETE_FAILED").contains(environment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_STATE_CONFLICT",
                    "The environment cannot be deleted in its current state.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID operationId = UUID.randomUUID();
        operations.insertDelete(operationId, id, now);
        environments.deleting(id, now);
        audit.recordDeletion(id, operationId, authentication.getName(), now);
        return new AcceptedEnvironmentResponse(id, operationId, "DELETING", "PENDING");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EnvironmentResponse get(UUID id, Authentication authentication) {
        authorization.requireAccount(authentication);
        var environment = environments.find(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found."));
        if (!authorization.hasRole(authentication, "CLEANUP")) {
            authorization.read(authentication, environment);
        }
        return response(environment);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationPage history(UUID id, int page, int size, Authentication authentication) {
        validatePage(page, size);
        var environment = environments.find(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found."));
        authorization.read(authentication, environment);
        long total = operations.count(id);
        return new OperationPage(operations.page(id, page, size).stream().map(operations::response).toList(),
                page, size, total, (total + size - 1) / size);
    }

    private AcceptedEnvironmentResponse replay(IdempotencyRepository.IdempotencyRecord previous,
            CreateEnvironmentRequest request, Authentication authentication) {
        var environment = environments.find(previous.environmentId()).orElseThrow();
        authorization.read(authentication, environment);
        if (!java.util.Objects.equals(request.teamId(), environment.teamId()) || !previous.matches(request)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was already used with a different request.");
        }
        return accepted(previous.environmentId(), previous.operationId());
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "page must be 0 to 1000000 and size must be 1 to 100.");
        }
    }

    private static AcceptedEnvironmentResponse accepted(UUID environmentId, UUID operationId) {
        return new AcceptedEnvironmentResponse(environmentId, operationId, "REQUESTED", "PENDING");
    }

    private EnvironmentResponse response(EnvironmentRecord record) {
        var latest = operations.page(record.id(), 0, 1).stream().findFirst()
                .map(operation -> new EnvironmentResponse.OperationSummary(operation.id(), operation.type(),
                        operation.status(), operation.progressStep(), operation.failureCode(),
                        operation.failureMessage())).orElse(null);
        return new EnvironmentResponse(record.id(), record.name(), record.namespace(), record.templateId(),
                record.status(), record.expiresAt(), record.createdAt(), record.updatedAt(), latest,
                record.teamId(), record.createdBy());
    }
}
