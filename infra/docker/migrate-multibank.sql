-- One-time, idempotent migration for the "real multi-bank aggregated dashboard" feature.
--
-- The deployed bankdb has a persistent volume, so init.sql does NOT re-run and these
-- schema changes must be applied by hand. Run BEFORE deploying the new banking-service
-- (the new code writes the added columns):
--
--   kubectl -n devops26 exec -i deploy/postgres -- psql -U bank -d bankdb < infra/docker/migrate-multibank.sql
--
-- Safe to run more than once.

-- Per-linked-bank data now lives on the connection row.
ALTER TABLE banking_connections ADD COLUMN IF NOT EXISTS account_name VARCHAR(100);
ALTER TABLE banking_connections ADD COLUMN IF NOT EXISTS balance NUMERIC(14,2);
ALTER TABLE banking_connections ADD COLUMN IF NOT EXISTS currency VARCHAR(10);

-- Real transaction descriptions (remittanceInformationUnstructured) exceed 40 chars.
ALTER TABLE transactions ALTER COLUMN category TYPE VARCHAR(255);

-- Tag each transaction with its source bank + connection (connection_id is the
-- replace-by-connection idempotency key used by the sync).
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_name VARCHAR(100);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS connection_id UUID;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS counterparty VARCHAR(100);

-- Drop the seeded fake transactions (they predate connection_id, so it is NULL).
-- Real transactions repopulate on the next sync.
DELETE FROM transactions WHERE connection_id IS NULL;

-- Neutralize the seeded fake identity on the anchor account; the next sync
-- overwrites customer_name/balance from real Enable Banking data.
UPDATE accounts
SET customer_name = 'My accounts', account_type = 'AGGREGATE', balance = 0.00, credit_limit = 0.00
WHERE id = '11111111-1111-1111-1111-111111111111' AND customer_name = 'Michael Carter';

-- Second aggregate account: the frontend's Live mode targets this id for real
-- production Enable Banking connections. Demo mode keeps targeting the original
-- 1111... anchor (where Mock ASPSP banks have historically been linked), so
-- public demos never show the real account's data.
-- (The deployed bankdb volume persists, so init.sql's seed does not re-run.)
INSERT INTO accounts (id, customer_name, account_type, balance, credit_limit)
VALUES
  ('22222222-2222-2222-2222-222222222222', 'My accounts', 'AGGREGATE', 0.00, 0.00)
ON CONFLICT (id) DO NOTHING;

-- If the row was created by an earlier revision of this migration (which
-- seeded it as the demo account), rename it to its live role.
UPDATE accounts
SET customer_name = 'My accounts'
WHERE id = '22222222-2222-2222-2222-222222222222' AND customer_name = 'Demo';
