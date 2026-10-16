package io.github.dcfaight.platformlab.environment;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserve(String key, CreateEnvironmentRequest request, UUID environmentId, UUID operationId) {
        // PostgreSQL waits for a competing transaction; rollback allows this request to become the winner.
        return jdbc.update("""
                INSERT INTO idempotency_requests (key, name, template_id, ttl_hours, environment_id, operation_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (key) DO NOTHING
                """, key, request.name(), request.templateId(), request.ttlHours(), environmentId, operationId) == 1;
    }

    public IdempotencyRecord find(String key) {
        return jdbc.queryForObject("SELECT * FROM idempotency_requests WHERE key = ?",
                (rs, row) -> new IdempotencyRecord(rs.getString("name"), rs.getString("template_id"),
                        rs.getInt("ttl_hours"), rs.getObject("environment_id", UUID.class),
                        rs.getObject("operation_id", UUID.class)), key);
    }

    public record IdempotencyRecord(String name, String templateId, int ttlHours,
            UUID environmentId, UUID operationId) {
        boolean matches(CreateEnvironmentRequest request) {
            return name.equals(request.name()) && templateId.equals(request.templateId())
                    && ttlHours == request.ttlHours();
        }
    }
}
