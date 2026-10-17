package io.github.dcfaight.platformlab.environment;

import io.github.dcfaight.platformlab.config.ApiIds;
import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.operation.OperationPage;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
            @Valid @RequestBody CreateEnvironmentRequest request, Authentication authentication) {
        var result = service.create(key, request, authentication);
        return ResponseEntity.accepted().location(URI.create("/api/v1/operations/" + result.operationId()))
                .body(result);
    }

    @GetMapping
    public EnvironmentPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        return service.list(page, size, authentication);
    }

    @GetMapping("/{id}")
    public EnvironmentResponse get(@PathVariable String id, Authentication authentication) {
        return service.get(ApiIds.parse(id), authentication);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AcceptedEnvironmentResponse> delete(@PathVariable String id,
            @RequestParam(required = false) String expiredBefore, Authentication authentication) {
        Instant cutoff = null;
        if (expiredBefore != null) {
            try {
                cutoff = Instant.parse(expiredBefore);
            } catch (DateTimeParseException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                        "expiredBefore must be an ISO instant with a timezone.");
            }
        }
        if (cutoff == null && authentication.getAuthorities().stream()
                .noneMatch(authority -> java.util.Set.of("ROLE_OPERATOR", "ROLE_DEVELOPER")
                        .contains(authority.getAuthority()))) {
            // Defense in depth: automation must never skip the expiry guard.
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "The authenticated user is not permitted to perform this operation.");
        }
        var result = service.delete(ApiIds.parse(id), cutoff, authentication);
        return ResponseEntity.accepted().location(URI.create("/api/v1/operations/" + result.operationId()))
                .body(result);
    }

    @GetMapping("/{id}/operations")
    public OperationPage history(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        return service.history(ApiIds.parse(id), page, size, authentication);
    }
}
