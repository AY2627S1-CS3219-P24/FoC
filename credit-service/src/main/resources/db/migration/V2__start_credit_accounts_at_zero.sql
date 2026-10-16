-- Preserve existing balances; only newly initialized accounts start at zero.
ALTER TABLE credit_accounts ALTER COLUMN available_balance SET DEFAULT 0;
