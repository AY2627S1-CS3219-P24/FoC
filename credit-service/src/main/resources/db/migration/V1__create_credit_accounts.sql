CREATE TABLE credit_accounts (
    user_id UUID PRIMARY KEY,
    available_balance BIGINT NOT NULL DEFAULT 100,
    reserved_balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT credit_accounts_available_nonnegative CHECK (available_balance >= 0),
    CONSTRAINT credit_accounts_reserved_nonnegative CHECK (reserved_balance >= 0)
);
