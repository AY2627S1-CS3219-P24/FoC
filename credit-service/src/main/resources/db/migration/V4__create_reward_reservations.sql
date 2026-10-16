CREATE TABLE credit_reserve_operations (
    operation_id UUID PRIMARY KEY,
    errand_id UUID NOT NULL UNIQUE,
    requester_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    outcome VARCHAR(40) NOT NULL DEFAULT 'PENDING' CHECK (outcome IN ('PENDING', 'RESERVED', 'INSUFFICIENT_BALANCE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK ((outcome = 'PENDING') = (completed_at IS NULL))
);
CREATE TABLE credit_reservations (
    errand_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES credit_reserve_operations(operation_id),
    user_id UUID NOT NULL REFERENCES credit_accounts(user_id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    state VARCHAR(20) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'REFUNDED', 'PAID')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,
    CHECK ((state = 'RESERVED') = (settled_at IS NULL))
);
CREATE TABLE credit_reserve_inbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES credit_reserve_operations(operation_id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);
CREATE INDEX credit_reserve_inbox_pending ON credit_reserve_inbox(next_attempt_at, event_id) WHERE processed_at IS NULL;
CREATE TABLE credit_result_outbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES credit_reserve_operations(operation_id),
    errand_id UUID NOT NULL,
    requester_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN ('RESERVED', 'INSUFFICIENT_BALANCE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX credit_result_outbox_pending ON credit_result_outbox(next_attempt_at, event_id) WHERE published_at IS NULL;
