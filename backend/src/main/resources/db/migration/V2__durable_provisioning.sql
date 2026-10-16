ALTER TABLE environments DROP CONSTRAINT environments_status_check;
ALTER TABLE environments ADD CONSTRAINT environments_status_check
    CHECK (status IN ('REQUESTED', 'PROVISIONING', 'READY', 'FAILED'));
ALTER TABLE operations DROP CONSTRAINT operations_status_check;
ALTER TABLE operations ADD CONSTRAINT operations_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'));
ALTER TABLE operations
    ADD COLUMN lease_token uuid,
    ADD COLUMN provisioned_at timestamptz,
    ADD COLUMN failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    ADD COLUMN failure_code varchar(64),
    ADD COLUMN failure_message varchar(256),
    ADD COLUMN progress_step varchar(32) NOT NULL DEFAULT 'ACCEPTED',
    ADD COLUMN correlation_id varchar(64);
CREATE INDEX operations_claim_idx ON operations (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE TABLE operation_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    type varchar(32) NOT NULL CHECK (type IN
        ('CLAIMED', 'PROVISIONED', 'WAITING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    code varchar(64) NOT NULL,
    message varchar(256) NOT NULL,
    correlation_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX operation_events_operation_idx ON operation_events (operation_id, id);
