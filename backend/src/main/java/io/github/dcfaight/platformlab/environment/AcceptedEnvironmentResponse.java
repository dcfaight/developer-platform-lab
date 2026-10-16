package io.github.dcfaight.platformlab.environment;

import java.util.UUID;

public record AcceptedEnvironmentResponse(UUID environmentId, UUID operationId,
        String environmentStatus, String operationStatus) {
}
