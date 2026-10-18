CREATE TABLE credit_settlement_operations (
    operation_id UUID PRIMARY KEY,
    reservation_operation_id UUID NOT NULL,
    errand_id UUID NOT NULL,
    requester_id UUID NOT NULL,
    courier_id UUID,
    amount BIGINT NOT NULL CHECK (amount > 0),
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN ('ErrandCancelledEvent', 'ErrandExpiredEvent', 'ErrandCompletedEvent')),
    outcome VARCHAR(40) NOT NULL DEFAULT 'PENDING' CHECK (outcome IN ('PENDING', 'REFUNDED', 'PAID', 'NO_RESERVATION', 'RESERVATION_MISMATCH', 'TERMINAL_CONFLICT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK ((event_type = 'ErrandCompletedEvent') = (courier_id IS NOT NULL)),
    CHECK ((outcome = 'PENDING') = (completed_at IS NULL))
);
CREATE TABLE credit_settlement_inbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES credit_settlement_operations(operation_id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);
CREATE INDEX credit_settlement_inbox_pending ON credit_settlement_inbox(next_attempt_at, event_id) WHERE processed_at IS NULL;

ALTER TABLE credit_reservations ADD COLUMN courier_id UUID REFERENCES credit_accounts(user_id);
ALTER TABLE credit_reservations ADD CONSTRAINT credit_reservations_paid_courier CHECK ((state = 'PAID') = (courier_id IS NOT NULL));

-- Keep the existing reservation result contract and its foreign key. Settlement results
-- share the same durable publisher but reference their own recorded operation.
ALTER TABLE credit_result_outbox ALTER COLUMN operation_id DROP NOT NULL;
ALTER TABLE credit_result_outbox ADD COLUMN settlement_operation_id UUID UNIQUE REFERENCES credit_settlement_operations(operation_id);
ALTER TABLE credit_result_outbox ADD COLUMN courier_id UUID;
ALTER TABLE credit_result_outbox ADD COLUMN settlement_type VARCHAR(10);
ALTER TABLE credit_result_outbox ADD CONSTRAINT credit_result_outbox_operation_kind CHECK (
    (operation_id IS NOT NULL AND settlement_operation_id IS NULL AND settlement_type IS NULL AND courier_id IS NULL)
    OR (operation_id IS NULL AND settlement_operation_id IS NOT NULL AND settlement_type IS NOT NULL AND
        ((settlement_type = 'REFUND' AND courier_id IS NULL) OR (settlement_type = 'PAYOUT' AND courier_id IS NOT NULL)))
);
ALTER TABLE credit_result_outbox DROP CONSTRAINT credit_result_outbox_outcome_check;
ALTER TABLE credit_result_outbox ADD CONSTRAINT credit_result_outbox_outcome_check CHECK (
    (operation_id IS NOT NULL AND outcome IN ('RESERVED', 'INSUFFICIENT_BALANCE'))
    OR (settlement_operation_id IS NOT NULL AND outcome IN ('REFUNDED', 'PAID', 'NO_RESERVATION', 'RESERVATION_MISMATCH', 'TERMINAL_CONFLICT'))
);
