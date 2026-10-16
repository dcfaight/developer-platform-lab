package io.github.dcfaight.platformlab.kubernetes;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;

public interface EnvironmentProvisioner {
    void provision(EnvironmentRecord environment);

    boolean isReady(EnvironmentRecord environment);
}
