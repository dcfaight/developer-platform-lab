package io.github.dcfaight.platformlab.worker;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerRepository {
    private final JdbcTemplate jdbc;

    public WorkerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Claim> claim(WorkerProperties properties) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT *, CASE
                        WHEN started_at + (? * interval '1 millisecond') <= clock_timestamp()
                            THEN CASE WHEN type = 'DELETE_ENVIRONMENT' THEN 'DELETION_TIMEOUT'
                                ELSE 'READINESS_TIMEOUT' END
                        WHEN type = 'CREATE_ENVIRONMENT' AND provisioned_at IS NULL
                            AND attempt_count >= ? THEN 'ATTEMPTS_EXHAUSTED'
                        END AS exhausted
                    FROM operations
                    WHERE (status = 'PENDING' AND next_attempt_at <= clock_timestamp())
                       OR (status = 'RUNNING' AND lease_expires_at <= clock_timestamp())
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                ), changed AS (
                    UPDATE operations o SET
                        status = CASE WHEN c.exhausted IS NULL THEN 'RUNNING' ELSE 'FAILED' END,
                        lease_token = CASE WHEN c.exhausted IS NULL THEN ?::uuid ELSE NULL END,
                        lease_expires_at = CASE WHEN c.exhausted IS NULL
                            THEN clock_timestamp() + (? * interval '1 millisecond') ELSE NULL END,
                        attempt_count = o.attempt_count + CASE
                            WHEN c.exhausted IS NULL AND o.provisioned_at IS NULL THEN 1 ELSE 0 END,
                        started_at = COALESCE(o.started_at, clock_timestamp()),
                        completed_at = CASE WHEN c.exhausted IS NOT NULL THEN clock_timestamp() ELSE NULL END,
                        failure_code = c.exhausted,
                        failure_message = CASE c.exhausted
                            WHEN 'DELETION_TIMEOUT' THEN 'The Kubernetes namespace did not disappear before the deletion deadline.'
                            WHEN 'READINESS_TIMEOUT' THEN 'The environment did not become ready before the provisioning deadline.'
                            WHEN 'ATTEMPTS_EXHAUSTED' THEN 'Provisioning did not complete within its attempt limits.'
                            ELSE NULL END,
                        progress_step = CASE WHEN c.exhausted IS NOT NULL THEN 'FAILED'
                            WHEN o.type = 'DELETE_ENVIRONMENT' THEN 'DELETING'
                            WHEN o.provisioned_at IS NULL THEN 'PROVISIONING' ELSE 'WAITING' END,
                        updated_at = clock_timestamp()
                    FROM candidate c WHERE o.id = c.id RETURNING o.*
                ), environment_change AS (
                    UPDATE environments e SET status = CASE
                        WHEN c.type = 'DELETE_ENVIRONMENT' THEN
                            CASE WHEN c.status = 'FAILED' THEN 'DELETE_FAILED' ELSE 'DELETING' END
                        WHEN c.status = 'FAILED' THEN 'FAILED' ELSE 'PROVISIONING' END,
                        updated_at = clock_timestamp()
                    FROM changed c WHERE e.id = c.environment_id
                ), event AS (
                    INSERT INTO operation_events (operation_id, type, code, message, correlation_id)
                    SELECT id, CASE WHEN status = 'FAILED' THEN 'FAILED' ELSE 'CLAIMED' END,
                        COALESCE(failure_code, 'CLAIMED'),
                        COALESCE(failure_message, 'Worker acquired the operation.'), correlation_id FROM changed
                )
                SELECT changed.*, (SELECT id FROM operations WHERE environment_id = changed.environment_id
                    AND type = 'CREATE_ENVIRONMENT') AS create_operation_id
                FROM changed
                """, (rs, row) -> new Claim(rs.getObject("id", UUID.class),
                rs.getObject("environment_id", UUID.class), rs.getObject("lease_token", UUID.class),
                rs.getString("status"), rs.getTimestamp("provisioned_at") != null,
                rs.getInt("failure_count"), rs.getString("type"),
                rs.getObject("create_operation_id", UUID.class), rs.getInt("attempt_count")),
                properties.readinessTimeout().toMillis(),
                properties.maxAttempts(), UUID.randomUUID(), properties.leaseDuration().toMillis())
                .stream().findFirst();
    }

    public boolean provisioned(Claim claim) {
        return transition(claim, "RUNNING", "WAITING", "PROVISIONED", "PROVISIONED",
                "Resources were provisioned; waiting for readiness.", null, null, Duration.ZERO, false, true);
    }

    public boolean waiting(Claim claim, Duration delay) {
        return transition(claim, "PENDING", "WAITING", "WAITING", "WAITING",
                "Resources are not ready yet.", null, null, delay, false, false);
    }

    public boolean deletionWaiting(Claim claim, Duration delay) {
        return transition(claim, "PENDING", "WAITING", "WAITING", "WAITING",
                "Waiting for the environment namespace to disappear.", null, null, delay, false, true);
    }

    public boolean succeed(Claim claim) {
        if (claim.deleting()) {
            return transition(claim, "SUCCEEDED", "DELETED", "SUCCEEDED", "SUCCEEDED",
                    "Environment namespace is absent.", null, null, Duration.ZERO, false, false);
        }
        return transition(claim, "SUCCEEDED", "READY", "SUCCEEDED", "SUCCEEDED",
                "Environment is ready.", null, null, Duration.ZERO, false, false);
    }

    public boolean fail(Claim claim, String code, String message) {
        return transition(claim, "FAILED", "FAILED", "FAILED", code, message,
                code, message, Duration.ZERO, false, false);
    }

    public boolean retry(Claim claim, String code, String message, Duration delay) {
        return transition(claim, "PENDING", "RETRY", "RETRY", code, message,
                code, message, delay, true, false);
    }

    public boolean owns(Claim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM operations WHERE id = ? AND status = 'RUNNING'
                    AND lease_token = ? AND lease_expires_at > clock_timestamp())
                """, Boolean.class, claim.id(), claim.leaseToken()));
    }

    private boolean transition(Claim claim, String status, String step, String event, String code,
            String message, String failureCode, String failureMessage, Duration delay,
            boolean incrementFailures, boolean provisioned) {
        return jdbc.queryForObject("""
                WITH changed AS (
                    UPDATE operations SET status = ?, progress_step = ?, failure_code = ?, failure_message = ?,
                        failure_count = failure_count + ?,
                        provisioned_at = CASE WHEN ? THEN clock_timestamp() ELSE provisioned_at END,
                        next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                        completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN clock_timestamp() ELSE NULL END,
                        lease_token = CASE WHEN ? = 'RUNNING' THEN lease_token ELSE NULL END,
                        lease_expires_at = CASE WHEN ? = 'RUNNING' THEN lease_expires_at ELSE NULL END,
                        updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                        AND lease_expires_at > clock_timestamp()
                    RETURNING *
                ), environment_change AS (
                    UPDATE environments e SET status = CASE WHEN c.type = 'DELETE_ENVIRONMENT' THEN
                        CASE c.status WHEN 'SUCCEEDED' THEN 'DELETED' WHEN 'FAILED' THEN 'DELETE_FAILED'
                            ELSE 'DELETING' END
                        ELSE CASE c.status
                        WHEN 'SUCCEEDED' THEN 'READY' WHEN 'FAILED' THEN 'FAILED' ELSE 'PROVISIONING' END
                        END,
                        updated_at = clock_timestamp()
                    FROM changed c WHERE e.id = c.environment_id
                ), event AS (
                    INSERT INTO operation_events (operation_id, type, code, message, correlation_id)
                    SELECT id, ?, ?, ?, correlation_id FROM changed
                )
                SELECT count(*) FROM changed
                """, Integer.class, status, step, failureCode, failureMessage, incrementFailures ? 1 : 0,
                provisioned, delay.toMillis(), status, status, status, claim.id(), claim.leaseToken(),
                event, code, message) == 1;
    }

    public record Claim(UUID id, UUID environmentId, UUID leaseToken, String status,
            boolean provisioned, int failureCount, String type, UUID createOperationId, int attemptCount) {
        public boolean deleting() {
            return "DELETE_ENVIRONMENT".equals(type);
        }
    }
}
