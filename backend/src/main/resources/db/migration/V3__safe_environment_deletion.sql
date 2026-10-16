ALTER TABLE environments DROP CONSTRAINT environments_status_check;
ALTER TABLE environments ADD CONSTRAINT environments_status_check
    CHECK (status IN ('REQUESTED', 'PROVISIONING', 'READY', 'FAILED',
                      'DELETING', 'DELETED', 'DELETE_FAILED'));
ALTER TABLE operations DROP CONSTRAINT operations_type_check;
ALTER TABLE operations ADD CONSTRAINT operations_type_check
    CHECK (type IN ('CREATE_ENVIRONMENT', 'DELETE_ENVIRONMENT'));
CREATE UNIQUE INDEX operations_one_active_environment_idx ON operations (environment_id)
    WHERE status IN ('PENDING', 'RUNNING');
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_check
    CHECK (action IN ('ENVIRONMENT_REQUESTED', 'ENVIRONMENT_DELETE_REQUESTED'));
