-- Permanent semantic deduplication evidence. Do not remove when pruning delivered outbox rows.
CREATE TABLE user_credit_provisioning (
    user_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE
);

CREATE TABLE user_created_outbox (
    event_id UUID PRIMARY KEY REFERENCES user_credit_provisioning (event_id),
    user_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    CONSTRAINT user_created_outbox_attempts_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT user_created_outbox_lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE INDEX user_created_outbox_pending ON user_created_outbox (next_attempt_at, event_id)
    WHERE published_at IS NULL;
