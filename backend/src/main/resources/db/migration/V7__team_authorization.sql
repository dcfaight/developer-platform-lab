CREATE TABLE platform_teams (
    id varchar(63) PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,62}$')
);

CREATE TABLE account_team_memberships (
    username varchar(64) NOT NULL CHECK (username ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$'),
    team_id varchar(63) NOT NULL REFERENCES platform_teams(id),
    PRIMARY KEY (username, team_id)
);

-- NULL ownership is intentional: existing records remain operator-managed.
ALTER TABLE environments ADD COLUMN team_id varchar(63) REFERENCES platform_teams(id);
ALTER TABLE environments ADD COLUMN created_by varchar(64);
CREATE INDEX environments_team_created_idx ON environments (team_id, created_at DESC, id DESC);

-- Retain historical reservations in the empty scope for operator-only compatibility.
ALTER TABLE idempotency_requests ADD COLUMN scope varchar(129) NOT NULL DEFAULT '';
ALTER TABLE idempotency_requests DROP CONSTRAINT idempotency_requests_pkey;
ALTER TABLE idempotency_requests ADD PRIMARY KEY (scope, key);
