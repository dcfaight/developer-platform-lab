package io.github.dcfaight.platformlab.kubernetes;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import java.util.UUID;

public interface EnvironmentProvisioner {
    void provision(EnvironmentRecord environment);

    default void provision(EnvironmentRecord environment, UUID createOperationId) {
        provision(environment);
    }

    default void provision(EnvironmentRecord environment, UUID createOperationId, OperationProgress leaseValid) {
        if (leaseValid.getAsBoolean()) {
            provision(environment, createOperationId);
        }
    }

    boolean delete(EnvironmentRecord environment, UUID createOperationId, OperationProgress leaseValid);

    boolean isReady(EnvironmentRecord environment);
}
