package io.github.dcfaight.platformlab.operation;

import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.config.ApiIds;
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

    public OperationController(OperationRepository operations) {
        this.operations = operations;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationResponse get(@PathVariable String id) {
        var record = operations.find(ApiIds.parse(id)).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Operation not found."));
        return operations.response(record);
    }
}
