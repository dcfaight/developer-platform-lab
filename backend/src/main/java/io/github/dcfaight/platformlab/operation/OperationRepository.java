package io.github.dcfaight.platformlab.operation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import io.github.dcfaight.platformlab.config.RequestCorrelationFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<OperationRecord> MAPPER = (rs, row) -> new OperationRecord(
            rs.getObject("id", UUID.class), rs.getObject("environment_id", UUID.class),
            rs.getString("type"), rs.getString("status"), rs.getInt("attempt_count"),
            instant(rs, "next_attempt_at"), instant(rs, "lease_expires_at"), instant(rs, "started_at"),
            instant(rs, "completed_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
            rs.getString("failure_code"), rs.getString("failure_message"), rs.getString("progress_step"),
            rs.getString("correlation_id"));

    public OperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID environmentId, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO operations (id, environment_id, type, status, attempt_count,
                    next_attempt_at, created_at, updated_at, correlation_id)
                VALUES (?, ?, 'CREATE_ENVIRONMENT', 'PENDING', 0, ?, ?, ?, ?)
                """, id, environmentId, timestamp, timestamp, timestamp,
                MDC.get(RequestCorrelationFilter.REQUEST_ID));
    }

    public Optional<OperationRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM operations WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<OperationRecord> active(UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM operations WHERE environment_id = ? AND status IN ('PENDING', 'RUNNING')
                """, MAPPER, environmentId).stream().findFirst();
    }

    public void insertDelete(UUID id, UUID environmentId, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO operations (id, environment_id, type, status,
                    next_attempt_at, created_at, updated_at, correlation_id)
                VALUES (?, ?, 'DELETE_ENVIRONMENT', 'PENDING', ?, ?, ?, ?)
                """, id, environmentId, timestamp, timestamp, timestamp,
                MDC.get(RequestCorrelationFilter.REQUEST_ID));
    }

    public List<OperationRecord> page(UUID environmentId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM operations WHERE environment_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, MAPPER, environmentId, size, (long) page * size);
    }

    public long count(UUID environmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM operations WHERE environment_id = ?",
                Long.class, environmentId);
    }

    public OperationResponse response(OperationRecord record) {
        return new OperationResponse(record.id(), record.environmentId(), record.type(), record.status(),
                record.attemptCount(), record.nextAttemptAt(), record.leaseExpiresAt(), record.startedAt(),
                record.completedAt(), record.createdAt(), record.updatedAt(), record.failureCode(),
                record.failureMessage(), record.progressStep(), record.correlationId(), events(record.id()));
    }

    public List<OperationEvent> events(UUID id) {
        return jdbc.query("""
                SELECT type, code, message, correlation_id, created_at
                FROM operation_events WHERE operation_id = ? ORDER BY id
                """, (rs, row) -> new OperationEvent(rs.getString("type"), rs.getString("code"),
                rs.getString("message"), rs.getString("correlation_id"), instant(rs, "created_at")), id);
    }

    public OperationMetrics metrics(boolean workerEnabled) {
        var types = jdbc.query("""
                WITH completed AS (
                    SELECT type, status, extract(epoch FROM completed_at - created_at) AS duration,
                        extract(epoch FROM started_at - created_at) AS queued
                    FROM operations
                )
                SELECT t.type,
                    count(c.status) FILTER (WHERE c.status = 'PENDING') AS pending,
                    count(c.status) FILTER (WHERE c.status = 'RUNNING') AS running,
                    count(c.status) FILTER (WHERE c.status = 'SUCCEEDED') AS succeeded,
                    count(c.status) FILTER (WHERE c.status = 'FAILED') AS failed,
                    round(avg(c.queued)::numeric, 3)::float8 AS average_queue,
                    count(c.duration) FILTER (WHERE c.status = 'SUCCEEDED') AS duration_count,
                    round(avg(c.duration) FILTER (WHERE c.status = 'SUCCEEDED')::numeric, 3)::float8 AS average,
                    round((percentile_cont(0.95) WITHIN GROUP (ORDER BY c.duration)
                        FILTER (WHERE c.status = 'SUCCEEDED'))::numeric, 3)::float8 AS p95,
                    round(max(c.duration) FILTER (WHERE c.status = 'SUCCEEDED')::numeric, 3)::float8 AS maximum
                FROM (VALUES ('CREATE_ENVIRONMENT'), ('DELETE_ENVIRONMENT')) AS t(type)
                LEFT JOIN completed c ON c.type = t.type
                GROUP BY t.type ORDER BY t.type
                """, (rs, row) -> new OperationMetrics.TypeMetrics(rs.getString("type"),
                rs.getLong("pending"), rs.getLong("running"), rs.getLong("succeeded"), rs.getLong("failed"),
                rs.getObject("average_queue", Double.class),
                new OperationMetrics.DurationSummary(rs.getLong("duration_count"),
                        rs.getObject("average", Double.class), rs.getObject("p95", Double.class),
                        rs.getObject("maximum", Double.class))));
        var worker = jdbc.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()) AS due,
                    count(*) FILTER (WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()) AS expired,
                    min(CASE WHEN status = 'PENDING' AND next_attempt_at <= clock_timestamp() THEN next_attempt_at
                        WHEN status = 'RUNNING' AND lease_expires_at <= clock_timestamp() THEN lease_expires_at
                        END) AS oldest_due,
                    (SELECT max(created_at) FROM operation_events) AS last_event
                FROM operations WHERE status IN ('PENDING', 'RUNNING')
                """, (rs, row) -> new OperationMetrics.WorkerHealth(rs.getLong("due"), rs.getLong("expired"),
                instant(rs, "oldest_due"), instant(rs, "last_event")));
        var failures = jdbc.query("""
                SELECT failure_code, count(*) AS failures FROM operations
                WHERE status = 'FAILED' AND failure_code IS NOT NULL
                GROUP BY failure_code ORDER BY failures DESC, failure_code LIMIT 20
                """, (rs, row) -> new OperationMetrics.FailureCount(rs.getString("failure_code"),
                rs.getLong("failures")));
        var now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        return new OperationMetrics(now, workerEnabled, types, worker, failures);
    }

    public record OperationEvent(String type, String code, String message, String correlationId, Instant createdAt) {
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record OperationRecord(UUID id, UUID environmentId, String type, String status,
            int attemptCount, Instant nextAttemptAt, Instant leaseExpiresAt, Instant startedAt,
            Instant completedAt, Instant createdAt, Instant updatedAt, String failureCode,
            String failureMessage, String progressStep, String correlationId) {
    }
}
