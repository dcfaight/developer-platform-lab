package io.github.dcfaight.platformlab.operation;

import java.time.Instant;
import java.util.UUID;

public record OperationResponse(UUID id, UUID environmentId, String type, String status,
        int attemptCount, Instant nextAttemptAt, Instant leaseExpiresAt, Instant startedAt,
        Instant completedAt, Instant createdAt, Instant updatedAt) {
}
