-- Complete work already acknowledged by the old consumer before removing its inbox.
INSERT INTO credit_accounts(user_id)
SELECT DISTINCT user_id FROM credit_provisioning_inbox WHERE processed_at IS NULL
ON CONFLICT(user_id) DO NOTHING;
DROP TABLE credit_provisioning_inbox;

-- Preserve owned credits while returning to the agreed account model.
ALTER TABLE credit_accounts ADD COLUMN total_balance BIGINT;
UPDATE credit_accounts SET total_balance = available_balance + reserved_balance;
ALTER TABLE credit_accounts ALTER COLUMN total_balance SET NOT NULL;
ALTER TABLE credit_accounts ALTER COLUMN total_balance SET DEFAULT 0;
ALTER TABLE credit_accounts ADD CONSTRAINT credit_accounts_total_nonnegative CHECK (total_balance >= 0);
ALTER TABLE credit_accounts DROP COLUMN available_balance;
ALTER TABLE credit_accounts DROP COLUMN reserved_balance;
