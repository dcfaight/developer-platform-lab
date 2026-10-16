package io.github.dcfaight.platformlab.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordCreation(UUID environmentId, UUID operationId, String actor, Instant now) {
        jdbc.update("""
                INSERT INTO audit_events (id, environment_id, operation_id, action, actor, created_at)
                VALUES (?, ?, ?, 'ENVIRONMENT_REQUESTED', ?, ?)
                """, UUID.randomUUID(), environmentId, operationId, actor, now.atOffset(ZoneOffset.UTC));
    }

    public void recordDeletion(UUID environmentId, UUID operationId, String actor, Instant now) {
        jdbc.update("""
                INSERT INTO audit_events (id, environment_id, operation_id, action, actor, created_at)
                VALUES (?, ?, ?, 'ENVIRONMENT_DELETE_REQUESTED', ?, ?)
                """, UUID.randomUUID(), environmentId, operationId, actor, now.atOffset(ZoneOffset.UTC));
    }
}
