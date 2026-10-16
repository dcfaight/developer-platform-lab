package io.github.dcfaight.platformlab.operation;

public enum OperationMilestone {
    PROVISION_STARTED("Provisioning resources for the environment."),
    NAMESPACE_CREATED("The environment namespace was created."),
    NAMESPACE_OBSERVED("An existing owned environment namespace was observed."),
    WORKLOAD_CREATION_STARTED("Creating the environment deployment."),
    WORKLOAD_UPDATE_STARTED("Reconciling the existing owned environment deployment."),
    DELETE_STARTED("Deleting the environment namespace.");

    private final String message;

    OperationMilestone(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
