package io.github.dcfaight.platformlab.kubernetes;

import java.util.function.BooleanSupplier;

/**
 * Worker callback passed to provisioner mutations. {@link #getAsBoolean()} reports whether the
 * caller still owns the operation lease and must be checked before each Kubernetes write;
 * {@link #milestone(Milestone)} records an accepted lifecycle step for operator timelines.
 */
@FunctionalInterface
public interface OperationProgress extends BooleanSupplier {
    default void milestone(Milestone milestone) {
    }

    enum Milestone {
        PROVISIONING_STARTED("Worker started provisioning environment resources."),
        NAMESPACE_CREATED("Kubernetes accepted the environment namespace."),
        WORKLOAD_CREATED("Kubernetes accepted the sample-service deployment."),
        DELETION_STARTED("Worker started deleting the environment namespace."),
        NAMESPACE_DELETE_REQUESTED("Kubernetes accepted the namespace deletion request.");

        private final String message;

        Milestone(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
