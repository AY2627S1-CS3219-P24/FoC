-- Do not discard acknowledged work when replacing the old inbox workers.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM credit_reserve_operations WHERE outcome = 'PENDING')
        OR EXISTS (SELECT 1 FROM credit_settlement_operations WHERE outcome = 'PENDING') THEN
        RAISE EXCEPTION 'Drain pending credit reservation and settlement work before upgrading';
    END IF;
END $$;

ALTER TABLE credit_reservations DROP CONSTRAINT credit_reservations_operation_id_fkey;
ALTER TABLE credit_reservations DROP CONSTRAINT credit_reservations_state_check;
ALTER TABLE credit_reservations ADD CONSTRAINT credit_reservations_state_check
    CHECK (state IN ('RESERVED', 'INSUFFICIENT_BALANCE', 'REFUNDED', 'PAID'));
INSERT INTO credit_reservations(errand_id, operation_id, user_id, amount, state, created_at, settled_at)
SELECT errand_id, operation_id, requester_id, amount, outcome, created_at, completed_at
FROM credit_reserve_operations WHERE outcome = 'INSUFFICIENT_BALANCE';

CREATE TABLE credit_transaction_log (
    operation_id UUID PRIMARY KEY,
    errand_id UUID NOT NULL REFERENCES credit_reservations(errand_id),
    user_id UUID NOT NULL REFERENCES credit_accounts(user_id),
    courier_id UUID REFERENCES credit_accounts(user_id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    transaction_type VARCHAR(10) NOT NULL CHECK (transaction_type IN ('RESERVE', 'REFUND', 'PAYMENT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((transaction_type = 'PAYMENT') = (courier_id IS NOT NULL))
);
-- One audit row per committed effect; duplicate terminal requests did not move credits again.
INSERT INTO credit_transaction_log(operation_id, errand_id, user_id, amount, transaction_type, created_at)
SELECT operation_id, errand_id, user_id, amount, 'RESERVE', created_at
FROM credit_reservations WHERE state <> 'INSUFFICIENT_BALANCE';
INSERT INTO credit_transaction_log(operation_id, errand_id, user_id, courier_id, amount, transaction_type, created_at)
SELECT DISTINCT ON (r.errand_id) s.operation_id, r.errand_id, r.user_id, r.courier_id, r.amount,
       CASE r.state WHEN 'PAID' THEN 'PAYMENT' ELSE 'REFUND' END, r.settled_at
FROM credit_reservations r JOIN credit_settlement_operations s ON s.errand_id = r.errand_id
WHERE r.state IN ('REFUNDED', 'PAID') AND s.outcome = r.state
ORDER BY r.errand_id, s.completed_at, s.operation_id;

-- Spring Modulith JDBC event publication registry.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date TIMESTAMPTZ,
    status TEXT,
    completion_attempts INTEGER,
    last_resubmission_date TIMESTAMPTZ
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication(completion_date);

-- Preserve settlement results still waiting for broker confirmation.
INSERT INTO event_publication(id, listener_id, event_type, serialized_event, publication_date,
                              completion_date, status, completion_attempts)
SELECT event_id, 'credit-settlement-rabbit', 'com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent',
       json_build_object('eventId', event_id, 'eventType', 'CreditSettlementResultEvent', 'schemaVersion', 1,
           'occurredAt', occurred_at, 'payload', json_build_object('operationId', settlement_operation_id,
               'errandId', errand_id, 'requesterId', requester_id, 'amount', amount, 'outcome', outcome,
               'settlementType', settlement_type, 'courierId', courier_id))::text,
       occurred_at, published_at, CASE WHEN published_at IS NULL THEN 'FAILED' ELSE 'COMPLETED' END, attempt_count
FROM credit_result_outbox WHERE settlement_operation_id IS NOT NULL;

DROP TABLE credit_result_outbox;
DROP TABLE credit_reserve_inbox;
DROP TABLE credit_settlement_inbox;
DROP TABLE credit_reserve_operations;
DROP TABLE credit_settlement_operations;
