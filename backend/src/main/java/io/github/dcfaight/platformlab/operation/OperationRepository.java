package io.github.dcfaight.platformlab.operation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 'CREATE_ENVIRONMENT', 'PENDING', 0, ?, ?, ?)
                """, id, environmentId, timestamp, timestamp, timestamp);
    }

    public Optional<OperationRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM operations WHERE id = ?",
                (rs, row) -> new OperationRecord(rs.getObject("id", UUID.class),
                        rs.getObject("environment_id", UUID.class), rs.getString("type"), rs.getString("status"),
                        rs.getInt("attempt_count"), instant(rs, "next_attempt_at"), instant(rs, "lease_expires_at"),
                        instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "created_at"),
                        instant(rs, "updated_at")), id).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record OperationRecord(UUID id, UUID environmentId, String type, String status,
            int attemptCount, Instant nextAttemptAt, Instant leaseExpiresAt, Instant startedAt,
            Instant completedAt, Instant createdAt, Instant updatedAt) {
    }
}
