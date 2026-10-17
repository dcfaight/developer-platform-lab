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

    public boolean reserve(String scope, String key, CreateEnvironmentRequest request, UUID environmentId, UUID operationId) {
        // PostgreSQL waits for a competing transaction; rollback allows this request to become the winner.
        return jdbc.update("""
                INSERT INTO idempotency_requests (scope, key, name, template_id, ttl_hours, environment_id, operation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (scope, key) DO NOTHING
                """, scope, key, request.name(), request.templateId(), request.ttlHours(), environmentId, operationId) == 1;
    }

    public java.util.Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.query("SELECT * FROM idempotency_requests WHERE scope = ? AND key = ?",
                (rs, row) -> new IdempotencyRecord(rs.getString("name"), rs.getString("template_id"),
                        rs.getInt("ttl_hours"), rs.getObject("environment_id", UUID.class),
                        rs.getObject("operation_id", UUID.class)), scope, key).stream().findFirst();
    }

    public record IdempotencyRecord(String name, String templateId, int ttlHours,
            UUID environmentId, UUID operationId) {
        boolean matches(CreateEnvironmentRequest request) {
            return name.equals(request.name()) && templateId.equals(request.templateId())
                    && ttlHours == request.ttlHours();
        }
    }
}
