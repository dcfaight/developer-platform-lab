package io.github.dcfaight.platformlab.operation;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import io.github.dcfaight.platformlab.operation.OperationRepository.OperationEvent;

public record OperationResponse(UUID id, UUID environmentId, String type, String status,
        int attemptCount, Instant nextAttemptAt, Instant leaseExpiresAt, Instant startedAt,
        Instant completedAt, Instant createdAt, Instant updatedAt, String failureCode,
        String failureMessage, String progressStep, String correlationId, List<OperationEvent> events) {
}
