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
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {
    private final JdbcTemplate jdbc;

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
        return jdbc.query("SELECT * FROM operations WHERE id = ?",
                (rs, row) -> new OperationRecord(rs.getObject("id", UUID.class),
                        rs.getObject("environment_id", UUID.class), rs.getString("type"), rs.getString("status"),
                        rs.getInt("attempt_count"), instant(rs, "next_attempt_at"), instant(rs, "lease_expires_at"),
                        instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "created_at"),
                        instant(rs, "updated_at"), rs.getString("failure_code"), rs.getString("failure_message"),
                        rs.getString("progress_step"), rs.getString("correlation_id")), id).stream().findFirst();
    }

    public List<OperationEvent> events(UUID id) {
        return jdbc.query("""
                SELECT type, code, message, correlation_id, created_at
                FROM operation_events WHERE operation_id = ? ORDER BY id
                """, (rs, row) -> new OperationEvent(rs.getString("type"), rs.getString("code"),
                rs.getString("message"), rs.getString("correlation_id"), instant(rs, "created_at")), id);
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
