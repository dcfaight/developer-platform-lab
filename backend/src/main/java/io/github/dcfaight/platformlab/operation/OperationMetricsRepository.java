package io.github.dcfaight.platformlab.operation;

import io.github.dcfaight.platformlab.operation.OperationMetricsController.Metrics;
import io.github.dcfaight.platformlab.operation.OperationMetricsController.StatusCounts;
import io.github.dcfaight.platformlab.operation.OperationMetricsController.TimingSummary;
import io.github.dcfaight.platformlab.operation.OperationMetricsController.WorkerSignals;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationMetricsRepository {
    private final JdbcTemplate jdbc;

    public OperationMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Metrics snapshot(boolean localWorkerEnabled) {
        // One statement gives all durable aggregates the same database snapshot and clock.
        return jdbc.queryForObject("""
                WITH samples AS (
                    SELECT status, lease_expires_at,
                        CASE WHEN status IN ('SUCCEEDED', 'FAILED')
                                  AND started_at IS NOT NULL AND completed_at IS NOT NULL
                            THEN GREATEST(0, EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)
                            END AS duration_ms,
                        CASE WHEN started_at IS NOT NULL
                            THEN GREATEST(0, EXTRACT(EPOCH FROM (started_at - created_at)) * 1000)
                            END AS queue_ms,
                        CASE WHEN status = 'PENDING'
                            THEN GREATEST(0, EXTRACT(EPOCH FROM (statement_timestamp() - created_at)) * 1000)
                            END AS pending_age_ms
                    FROM operations
                )
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                    count(*) FILTER (WHERE status = 'RUNNING') AS running,
                    count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                    count(*) FILTER (WHERE status = 'FAILED') AS failed,
                    count(duration_ms) AS duration_count,
                    COALESCE(avg(duration_ms), 0) AS duration_avg,
                    COALESCE(max(duration_ms), 0) AS duration_max,
                    count(queue_ms) AS queue_count,
                    COALESCE(avg(queue_ms), 0) AS queue_avg,
                    COALESCE(max(queue_ms), 0) AS queue_max,
                    count(*) FILTER (WHERE status = 'RUNNING'
                        AND lease_expires_at <= statement_timestamp()) AS expired_leases,
                    COALESCE(max(pending_age_ms), 0) AS oldest_pending_age
                FROM samples
                """, (rs, row) -> new Metrics(
                new StatusCounts(rs.getLong("pending"), rs.getLong("running"),
                        rs.getLong("succeeded"), rs.getLong("failed")),
                new TimingSummary(rs.getLong("duration_count"), rs.getDouble("duration_avg"),
                        rs.getDouble("duration_max")),
                new TimingSummary(rs.getLong("queue_count"), rs.getDouble("queue_avg"),
                        rs.getDouble("queue_max")),
                new WorkerSignals(localWorkerEnabled, rs.getLong("expired_leases"),
                        rs.getDouble("oldest_pending_age"))));
    }
}
