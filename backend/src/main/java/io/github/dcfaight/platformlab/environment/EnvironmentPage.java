package io.github.dcfaight.platformlab.environment;

import java.util.List;

public record EnvironmentPage(List<EnvironmentResponse> items, int page, int size,
        long totalElements, long totalPages) {
}
