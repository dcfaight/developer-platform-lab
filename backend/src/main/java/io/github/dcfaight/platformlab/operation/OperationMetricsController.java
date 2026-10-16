package io.github.dcfaight.platformlab.operation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationMetricsController {
    private final OperationMetricsRepository metrics;
    private final boolean localWorkerEnabled;

    public OperationMetricsController(OperationMetricsRepository metrics,
            @Value("${platform.worker.enabled:false}") boolean localWorkerEnabled) {
        this.metrics = metrics;
        this.localWorkerEnabled = localWorkerEnabled;
    }

    @GetMapping("/api/v1/operations/metrics")
    public Metrics get() {
        return metrics.snapshot(localWorkerEnabled);
    }

    public record Metrics(StatusCounts counts, TimingSummary completedDuration,
            TimingSummary initialQueueDelay, WorkerSignals worker) {}

    public record StatusCounts(long PENDING, long RUNNING, long SUCCEEDED, long FAILED) {}

    /**
     * Milliseconds, clamped to zero for inverted timestamps; empty populations report zero.
     * Completed duration includes SUCCEEDED/FAILED rows with both start and completion timestamps.
     * Initial queue delay includes all rows with a first start timestamp, including queued retries.
     * Durations span retries/readiness waits, not individual attempts or CPU execution time.
     */
    public record TimingSummary(long count, double averageMillis, double maxMillis) {}

    /** Local configuration is not global worker health. Pending age includes delayed retries. */
    public record WorkerSignals(boolean localWorkerEnabled, long expiredRunningLeases,
            double oldestPendingAgeMillis) {}
}
