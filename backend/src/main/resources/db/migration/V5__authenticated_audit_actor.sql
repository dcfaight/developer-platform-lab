-- Audit actors are now authenticated usernames. Historical rows keep the legacy
-- 'local-development' value, which configured usernames may not reuse.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_actor_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_actor_check
    CHECK (actor ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$');
