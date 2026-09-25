ALTER TABLE users ADD COLUMN avatar_key VARCHAR(40);

CREATE TABLE avatar_cleanup_tasks (
    avatar_key VARCHAR(40) PRIMARY KEY,
    eligible_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX avatar_cleanup_eligible_at_idx ON avatar_cleanup_tasks (eligible_at);
