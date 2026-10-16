package io.github.dcfaight.platformlab.environment;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentResponse(UUID id, String name, String namespace, String templateId,
        String status, Instant expiresAt, Instant createdAt, Instant updatedAt, OperationSummary latestOperation) {
    public record OperationSummary(UUID id, String type, String status, String progressStep,
            String failureCode, String failureMessage) {
    }
}
