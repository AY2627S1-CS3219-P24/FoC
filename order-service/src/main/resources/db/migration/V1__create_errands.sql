CREATE TABLE errands (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    courier_id UUID,
    supplier_id UUID NOT NULL,
    pickup_name VARCHAR(255) NOT NULL,
    pickup_address VARCHAR(1000) NOT NULL,
    delivery_location VARCHAR(500) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    notes VARCHAR(2000) NOT NULL DEFAULT '',
    credit_reward BIGINT NOT NULL CHECK (credit_reward > 0),
    create_request_id UUID NOT NULL,
    settlement_event_id UUID UNIQUE,
    status VARCHAR(24) NOT NULL,
    credit_state VARCHAR(24),
    creation_failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    accepted_at TIMESTAMPTZ,
    picked_up_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    CONSTRAINT errands_request_unique UNIQUE (requester_id, create_request_id),
    CONSTRAINT errands_distinct_participants CHECK (courier_id IS NULL OR courier_id <> requester_id),
    CONSTRAINT errands_state_credit CHECK (
        (status IN ('CREATING', 'CREATE_FAILED') AND credit_state IS NULL)
        OR (status IN ('OPEN', 'ACCEPTED', 'PICKED_UP') AND credit_state IS NOT NULL AND credit_state = 'RESERVED')
        OR (status = 'COMPLETED' AND credit_state IS NOT NULL AND credit_state IN ('SETTLEMENT_PENDING', 'SETTLED'))
        OR (status IN ('CANCELLED', 'EXPIRED') AND credit_state IS NOT NULL AND credit_state IN ('RELEASE_PENDING', 'RELEASED'))
    ),
    CONSTRAINT errands_courier CHECK ((status IN ('ACCEPTED', 'PICKED_UP', 'COMPLETED')) = (courier_id IS NOT NULL)),
    CONSTRAINT errands_terminal_event CHECK ((status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')) = (settlement_event_id IS NOT NULL)),
    CONSTRAINT errands_creation_failure CHECK ((status = 'CREATE_FAILED') = (creation_failure_code IS NOT NULL))
);
CREATE INDEX errands_requester_created_idx ON errands (requester_id, created_at DESC);
CREATE INDEX errands_courier_created_idx ON errands (courier_id, created_at DESC);
CREATE INDEX errands_open_created_idx ON errands (created_at DESC) WHERE status = 'OPEN';
CREATE INDEX errands_open_expiry_idx ON errands (expires_at) WHERE status = 'OPEN';
