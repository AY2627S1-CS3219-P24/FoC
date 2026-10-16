CREATE TABLE credit_provisioning_inbox (
    event_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);

CREATE INDEX credit_provisioning_inbox_pending
    ON credit_provisioning_inbox (received_at, event_id) WHERE processed_at IS NULL;
