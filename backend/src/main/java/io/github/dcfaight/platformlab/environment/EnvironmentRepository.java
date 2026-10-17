package io.github.dcfaight.platformlab.environment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EnvironmentRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<EnvironmentRecord> MAPPER = (rs, row) -> new EnvironmentRecord(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("namespace"),
            rs.getString("template_id"), rs.getString("status"), instant(rs, "expires_at"),
            instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("team_id"), rs.getString("created_by"));

    public EnvironmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EnvironmentRecord environment) {
        jdbc.update("""
                INSERT INTO environments (id, name, namespace, template_id, status, expires_at, created_at, updated_at,
                    team_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, environment.id(), environment.name(), environment.namespace(), environment.templateId(),
                environment.status(), utc(environment.expiresAt()), utc(environment.createdAt()),
                utc(environment.updatedAt()), environment.teamId(), environment.createdBy());
    }

    public Optional<EnvironmentRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM environments WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<EnvironmentRecord> lock(UUID id) {
        return jdbc.query("SELECT * FROM environments WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public void deleting(UUID id, Instant now) {
        jdbc.update("UPDATE environments SET status = 'DELETING', updated_at = ? WHERE id = ?", utc(now), id);
    }

    public List<EnvironmentRecord> page(int page, int size) {
        return jdbc.query("SELECT * FROM environments ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, size, (long) page * size);
    }

    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM environments", Long.class);
    }

    public List<EnvironmentRecord> pageForAccount(String username, int page, int size) {
        return jdbc.query("""
                SELECT e.* FROM environments e WHERE EXISTS (
                    SELECT 1 FROM account_team_memberships m WHERE m.username = ? AND m.team_id = e.team_id)
                ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?
                """, MAPPER, username, size, (long) page * size);
    }

    public long countForAccount(String username) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM environments e WHERE EXISTS (
                    SELECT 1 FROM account_team_memberships m WHERE m.username = ? AND m.team_id = e.team_id)
                """, Long.class, username);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record EnvironmentRecord(UUID id, String name, String namespace, String templateId,
            String status, Instant expiresAt, Instant createdAt, Instant updatedAt, String teamId, String createdBy) {
        public EnvironmentRecord(UUID id, String name, String namespace, String templateId,
                String status, Instant expiresAt, Instant createdAt, Instant updatedAt) {
            this(id, name, namespace, templateId, status, expiresAt, createdAt, updatedAt, null, null);
        }
    }
}
