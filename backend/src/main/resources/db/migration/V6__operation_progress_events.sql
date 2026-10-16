-- PROGRESS events record lifecycle milestones that do not change operation status.
ALTER TABLE operation_events DROP CONSTRAINT operation_events_type_check;
ALTER TABLE operation_events ADD CONSTRAINT operation_events_type_check CHECK (type IN
    ('CLAIMED', 'PROGRESS', 'PROVISIONED', 'WAITING', 'RETRY', 'SUCCEEDED', 'FAILED'));
CREATE INDEX operation_events_created_idx ON operation_events (created_at);
