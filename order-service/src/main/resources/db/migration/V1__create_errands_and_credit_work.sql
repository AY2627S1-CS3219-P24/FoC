CREATE TABLE errands (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    reservation_operation_id UUID NOT NULL UNIQUE,
    pickup VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    reward BIGINT NOT NULL CHECK (reward > 0),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_CREDIT'
        CHECK (status IN ('PENDING_CREDIT', 'OPEN', 'CREDIT_REJECTED', 'ACCEPTED', 'CANCELLED', 'EXPIRED', 'COMPLETED')),
    credit_outcome VARCHAR(30) CHECK (credit_outcome IN ('RESERVED', 'INSUFFICIENT_BALANCE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (requester_id, idempotency_key),
    CHECK (status <> 'OPEN' OR (credit_outcome IS NOT NULL AND credit_outcome = 'RESERVED')),
    CHECK (status <> 'CREDIT_REJECTED' OR (credit_outcome IS NOT NULL AND credit_outcome = 'INSUFFICIENT_BALANCE'))
);
CREATE TABLE errand_submission_outbox (
    event_id UUID PRIMARY KEY,
    errand_id UUID NOT NULL UNIQUE REFERENCES errands(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(255)
);
CREATE INDEX errand_submission_outbox_pending ON errand_submission_outbox(next_attempt_at, event_id) WHERE published_at IS NULL;
CREATE TABLE credit_reservation_result_inbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    errand_id UUID NOT NULL REFERENCES errands(id),
    requester_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    outcome VARCHAR(30) NOT NULL CHECK (outcome IN ('RESERVED', 'INSUFFICIENT_BALANCE')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);
CREATE INDEX credit_reservation_result_inbox_pending ON credit_reservation_result_inbox(received_at, event_id) WHERE processed_at IS NULL;
