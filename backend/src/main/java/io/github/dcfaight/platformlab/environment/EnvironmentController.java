package io.github.dcfaight.platformlab.environment;

import io.github.dcfaight.platformlab.config.ApiIds;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {
    private final EnvironmentService service;

    public EnvironmentController(EnvironmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AcceptedEnvironmentResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateEnvironmentRequest request) {
        var result = service.create(key, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/operations/" + result.operationId()))
                .body(result);
    }

    @GetMapping
    public EnvironmentPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public EnvironmentResponse get(@PathVariable String id) {
        return service.get(ApiIds.parse(id));
    }
}
