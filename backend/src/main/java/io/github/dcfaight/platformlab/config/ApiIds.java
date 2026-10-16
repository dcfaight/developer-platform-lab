package io.github.dcfaight.platformlab.config;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class ApiIds {
    private ApiIds() {
    }

    public static UUID parse(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "id must be a canonical UUID.");
        }
        return UUID.fromString(value);
    }
}
