package io.github.dcfaight.platformlab.kubernetes;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import io.github.dcfaight.platformlab.operation.OperationMilestone;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public interface EnvironmentProvisioner {
    void provision(EnvironmentRecord environment);

    default void provision(EnvironmentRecord environment, UUID createOperationId) {
        provision(environment);
    }

    default void provision(EnvironmentRecord environment, UUID createOperationId, BooleanSupplier leaseValid) {
        if (leaseValid.getAsBoolean()) {
            provision(environment, createOperationId);
        }
    }

    default void provision(EnvironmentRecord environment, UUID createOperationId, BooleanSupplier leaseValid,
            Predicate<OperationMilestone> progress) {
        provision(environment, createOperationId, leaseValid);
    }

    boolean delete(EnvironmentRecord environment, UUID createOperationId, BooleanSupplier leaseValid);

    boolean isReady(EnvironmentRecord environment);
}
