package io.github.dcfaight.platformlab.environment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateEnvironmentRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9 _-]{0,62}",
                message = "must be 1 to 63 ASCII letters, digits, spaces, underscores or hyphens, starting with a letter or digit")
        String name,
        @NotNull @Pattern(regexp = "sample-service", message = "must be sample-service")
        String templateId,
        @Min(1) @Max(24) Integer ttlHours,
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String teamId) {
    public CreateEnvironmentRequest(String name, String templateId, Integer ttlHours) {
        this(name, templateId, ttlHours, null);
    }

    public CreateEnvironmentRequest {
        name = name == null ? null : name.strip();
        ttlHours = ttlHours == null ? 8 : ttlHours;
    }
}
