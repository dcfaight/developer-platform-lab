package io.github.dcfaight.platformlab.operation;

import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.config.ApiIds;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.security.TeamAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationController {
    private final OperationRepository operations;
    private final EnvironmentRepository environments;
    private final TeamAuthorization authorization;

    private final boolean workerEnabled;

    public OperationController(OperationRepository operations, EnvironmentRepository environments, TeamAuthorization authorization,
            @Value("${platform.worker.enabled:false}") boolean workerEnabled) {
        this.operations = operations;
        this.environments = environments;
        this.authorization = authorization;
        this.workerEnabled = workerEnabled;
    }

    @GetMapping("/metrics")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationMetrics metrics() {
        return operations.metrics(workerEnabled);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationResponse get(@PathVariable String id, Authentication authentication) {
        var record = operations.find(ApiIds.parse(id)).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Operation not found."));
        authorization.read(authentication, environments.find(record.environmentId()).orElseThrow());
        return operations.response(record);
    }
}
