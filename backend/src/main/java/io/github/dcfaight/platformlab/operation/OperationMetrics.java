package io.github.dcfaight.platformlab.operation;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time operation metrics derived from durable PostgreSQL state, so values are
 * accurate across backend restarts and do not depend on which replica runs the worker.
 */
public record OperationMetrics(Instant generatedAt, boolean workerEnabled, List<TypeMetrics> operations,
        WorkerHealth worker, List<FailureCount> failureCodes) {

    /** Status counts and durations for one operation type. Durations are in seconds. */
    public record TypeMetrics(String type, long pending, long running, long succeeded, long failed,
            Double averageQueueSeconds, DurationSummary succeededDuration) {
    }

    /** End-to-end duration (acceptance to completion) of succeeded operations. */
    public record DurationSummary(long count, Double averageSeconds, Double p95Seconds, Double maxSeconds) {
    }

    /**
     * {@code dueOperations} are claimable now; {@code expiredLeases} are RUNNING operations whose
     * worker stopped renewing them. A growing backlog or old {@code oldestDueAt} indicates an
     * unhealthy or disabled worker.
     */
    public record WorkerHealth(long dueOperations, long expiredLeases, Instant oldestDueAt, Instant lastEventAt) {
    }

    public record FailureCount(String code, long count) {
    }
}
