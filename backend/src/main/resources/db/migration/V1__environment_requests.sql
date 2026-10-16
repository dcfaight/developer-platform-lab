CREATE TABLE environments (
    id uuid PRIMARY KEY,
    name varchar(63) NOT NULL CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9 _-]{0,62}$'),
    namespace varchar(63) NOT NULL UNIQUE,
    template_id varchar(64) NOT NULL CHECK (template_id = 'sample-service'),
    status varchar(32) NOT NULL CHECK (status = 'REQUESTED'),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (namespace = 'env-' || replace(id::text, '-', ''))
);

CREATE INDEX environments_created_id_idx ON environments (created_at DESC, id DESC);

CREATE TABLE operations (
    id uuid PRIMARY KEY,
    environment_id uuid NOT NULL REFERENCES environments(id),
    type varchar(32) NOT NULL CHECK (type = 'CREATE_ENVIRONMENT'),
    status varchar(32) NOT NULL CHECK (status = 'PENDING'),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_expires_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, environment_id)
);

CREATE TABLE idempotency_requests (
    key varchar(128) PRIMARY KEY CHECK (key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    name varchar(63) NOT NULL,
    template_id varchar(64) NOT NULL,
    ttl_hours integer NOT NULL CHECK (ttl_hours BETWEEN 1 AND 24),
    environment_id uuid NOT NULL REFERENCES environments(id) DEFERRABLE INITIALLY DEFERRED,
    operation_id uuid NOT NULL,
    FOREIGN KEY (operation_id, environment_id) REFERENCES operations(id, environment_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    environment_id uuid NOT NULL REFERENCES environments(id),
    operation_id uuid NOT NULL,
    action varchar(64) NOT NULL CHECK (action = 'ENVIRONMENT_REQUESTED'),
    actor varchar(64) NOT NULL CHECK (actor = 'local-development'),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (operation_id, environment_id) REFERENCES operations(id, environment_id)
);
