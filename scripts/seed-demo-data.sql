-- Seed the DEMO aggregate account (11111111-1111-1111-1111-111111111111) with a set of
-- realistic-looking bank connections and ~6 months of transactions so the dashboard has
-- something to draw.
--
-- Idempotent: wipes and re-inserts the seed rows on every run, keyed by well-known
-- connection IDs / a "SEED:" prefix on transactions.id. Safe against real Enable Banking
-- data on the live account (22222222-...) — this script only touches the demo aggregate.
--
-- Usage:
--   Get-Content scripts\seed-demo-data.sql | docker compose --env-file .env exec -T database psql -U admin-bankuser -d bankdb

BEGIN;

-- Fixed IDs for the seeded connections. Keeping them stable makes the script idempotent
-- (ON CONFLICT (id) DO UPDATE) and lets us wipe just our seeded transactions.
DO $$
DECLARE
  demo_account UUID := '11111111-1111-1111-1111-111111111111';
  con_n26      UUID := 'dddddddd-0000-0000-0000-000000000001';
  con_dkb      UUID := 'dddddddd-0000-0000-0000-000000000002';
  con_sparka   UUID := 'dddddddd-0000-0000-0000-000000000003';
  con_revolut  UUID := 'dddddddd-0000-0000-0000-000000000004';
BEGIN

  -- 1) Wipe any prior seeded connections + their transactions (keeps the demo idempotent).
  DELETE FROM transactions
   WHERE connection_id IN (con_n26, con_dkb, con_sparka, con_revolut);

  DELETE FROM banking_connections
   WHERE id IN (con_n26, con_dkb, con_sparka, con_revolut);

  -- Also clean up any pre-existing "seed-demo-mock" row from scripts/seed-vm-mock.sql so
  -- the demo dashboard doesn't show two "Mock ASPSP" entries.
  DELETE FROM transactions
   WHERE connection_id IN (SELECT id FROM banking_connections WHERE state = 'seed-demo-mock');
  DELETE FROM banking_connections WHERE state = 'seed-demo-mock';

  -- 2) Insert 4 mock bank connections on the demo account with realistic EUR balances.
  INSERT INTO banking_connections
    (id, account_id, session_id, bank_name, country, state,
     external_account_uid, account_name, balance, currency, status, valid_until)
  VALUES
    (con_n26,     demo_account, 'seed-n26',     'N26',            'DE', 'seed-demo-n26',
     'ext-n26-001',     'Main account',     4820.55, 'EUR', 'ACTIVE', NOW() + INTERVAL '90 days'),
    (con_dkb,     demo_account, 'seed-dkb',     'DKB',            'DE', 'seed-demo-dkb',
     'ext-dkb-001',     'Girokonto',        2130.10, 'EUR', 'ACTIVE', NOW() + INTERVAL '90 days'),
    (con_sparka,  demo_account, 'seed-sparka',  'Sparkasse',      'DE', 'seed-demo-sparka',
     'ext-sparka-001',  'Savings',          8760.00, 'EUR', 'ACTIVE', NOW() + INTERVAL '90 days'),
    (con_revolut, demo_account, 'seed-revolut', 'Revolut',        'DE', 'seed-demo-revolut',
     'ext-revolut-001', 'Everyday EUR',      540.75, 'EUR', 'ACTIVE', NOW() + INTERVAL '90 days');

  -- 3) Update the aggregate account so the dashboard's "Total balance" matches the sum.
  UPDATE accounts
     SET balance = 4820.55 + 2130.10 + 8760.00 + 540.75,
         customer_name = 'Demo user',
         updated_at = NOW()
   WHERE id = demo_account;

  -- 4) Six months of transactions (Feb..Jul 2026 relative to the seed date). Amounts and
  --    categories are chosen to look like a real month for a Munich-based tenant:
  --    salary credit, rent debit, weekly groceries, subscriptions, occasional dining/transport.
  --
  --    Category tags come from what the transaction-service groups by (category column).

  -- Helper: build a timestamp N months ago on a given day.
  -- We use explicit dates instead of generate_series() so the story is easy to read.

  -- ===== Salary (main N26 account) — monthly on the 28th =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-02-28' + TIME '09:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-03-28' + TIME '09:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-04-28' + TIME '09:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-05-28' + TIME '09:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-06-28' + TIME '09:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Salary',
     'TUM Payroll',      3200.00, 'CREDIT', DATE '2026-07-01' + TIME '09:00');

  -- ===== Rent (DKB) — monthly on the 1st =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-02-01' + TIME '08:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-03-01' + TIME '08:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-04-01' + TIME '08:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-05-01' + TIME '08:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-06-01' + TIME '08:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Rent',
     'Vermieter GmbH',   1180.00, 'DEBIT', DATE '2026-07-01' + TIME '08:00');

  -- ===== Groceries (N26) — a few per month =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      62.40, 'DEBIT', TIMESTAMP '2026-07-03 18:45'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'Aldi Süd',  38.19, 'DEBIT', TIMESTAMP '2026-07-08 19:10'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      54.80, 'DEBIT', TIMESTAMP '2026-07-11 17:22'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'Lidl',      41.75, 'DEBIT', TIMESTAMP '2026-06-05 12:05'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      71.30, 'DEBIT', TIMESTAMP '2026-06-15 18:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'Aldi Süd',  33.60, 'DEBIT', TIMESTAMP '2026-05-14 19:40'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      58.20, 'DEBIT', TIMESTAMP '2026-05-22 17:55'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'Lidl',      44.10, 'DEBIT', TIMESTAMP '2026-04-09 16:30'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      63.90, 'DEBIT', TIMESTAMP '2026-04-19 18:20'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Groceries', 'REWE',      48.00, 'DEBIT', TIMESTAMP '2026-03-12 18:00');

  -- ===== Subscriptions (Revolut) — monthly on/around the 10th =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Netflix',       12.99, 'DEBIT', TIMESTAMP '2026-07-10 03:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Spotify',        9.99, 'DEBIT', TIMESTAMP '2026-07-06 03:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'GitHub Copilot', 19.00, 'DEBIT', TIMESTAMP '2026-07-14 12:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Netflix',       12.99, 'DEBIT', TIMESTAMP '2026-06-10 03:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Spotify',        9.99, 'DEBIT', TIMESTAMP '2026-06-06 03:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Netflix',       12.99, 'DEBIT', TIMESTAMP '2026-05-10 03:00'),
    (gen_random_uuid(), demo_account, con_revolut, 'Revolut', 'Subscription', 'Spotify',        9.99, 'DEBIT', TIMESTAMP '2026-05-06 03:00');

  -- ===== Utilities (DKB) — monthly =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Utilities', 'Stadtwerke München',  84.60, 'DEBIT', TIMESTAMP '2026-07-05 10:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Utilities', 'Stadtwerke München',  84.60, 'DEBIT', TIMESTAMP '2026-06-05 10:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Utilities', 'Stadtwerke München',  81.20, 'DEBIT', TIMESTAMP '2026-05-05 10:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Utilities', 'Vodafone Internet',   29.99, 'DEBIT', TIMESTAMP '2026-07-15 09:00'),
    (gen_random_uuid(), demo_account, con_dkb, 'DKB', 'Utilities', 'Vodafone Internet',   29.99, 'DEBIT', TIMESTAMP '2026-06-15 09:00');

  -- ===== Dining (N26) — occasional =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Dining', 'L''Osteria',        42.50, 'DEBIT', TIMESTAMP '2026-07-12 20:30'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Dining', 'Hofbräuhaus',       28.90, 'DEBIT', TIMESTAMP '2026-07-07 19:15'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Dining', 'Café Frischhut',    12.30, 'DEBIT', TIMESTAMP '2026-06-22 11:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Dining', 'Vapiano',           24.80, 'DEBIT', TIMESTAMP '2026-06-14 20:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Dining', 'Sushiya',           35.00, 'DEBIT', TIMESTAMP '2026-05-30 19:45');

  -- ===== Transport (N26) — MVV pass + occasional Uber =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Transport', 'MVV Monatskarte', 58.30, 'DEBIT', TIMESTAMP '2026-07-01 07:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Transport', 'MVV Monatskarte', 58.30, 'DEBIT', TIMESTAMP '2026-06-01 07:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Transport', 'MVV Monatskarte', 58.30, 'DEBIT', TIMESTAMP '2026-05-01 07:00'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Transport', 'Uber',            18.40, 'DEBIT', TIMESTAMP '2026-07-13 23:20'),
    (gen_random_uuid(), demo_account, con_n26, 'N26', 'Transport', 'Deutsche Bahn',   79.90, 'DEBIT', TIMESTAMP '2026-06-08 08:30');

  -- ===== Savings transfer (Sparkasse) — monthly credit into savings =====
  INSERT INTO transactions (id, account_id, connection_id, bank_name, category,
                            counterparty, amount, direction, created_at) VALUES
    (gen_random_uuid(), demo_account, con_sparka, 'Sparkasse', 'Transfer', 'To savings',  500.00, 'CREDIT', TIMESTAMP '2026-07-02 08:00'),
    (gen_random_uuid(), demo_account, con_sparka, 'Sparkasse', 'Transfer', 'To savings',  500.00, 'CREDIT', TIMESTAMP '2026-06-02 08:00'),
    (gen_random_uuid(), demo_account, con_sparka, 'Sparkasse', 'Transfer', 'To savings',  500.00, 'CREDIT', TIMESTAMP '2026-05-02 08:00'),
    (gen_random_uuid(), demo_account, con_sparka, 'Sparkasse', 'Transfer', 'To savings',  500.00, 'CREDIT', TIMESTAMP '2026-04-02 08:00');

END $$;

COMMIT;

-- Quick verification (safe to run — read-only after commit).
SELECT bank_name, currency, balance, status
  FROM banking_connections
 WHERE account_id = '11111111-1111-1111-1111-111111111111'
 ORDER BY balance DESC;

SELECT COUNT(*) AS demo_transactions
  FROM transactions
 WHERE account_id = '11111111-1111-1111-1111-111111111111';
