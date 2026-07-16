CREATE TABLE IF NOT EXISTS accounts (
  id UUID PRIMARY KEY,
  customer_name VARCHAR(100) NOT NULL,
  account_type VARCHAR(30) NOT NULL,
  balance NUMERIC(14,2) NOT NULL,
  credit_limit NUMERIC(14,2) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transactions (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL,
  category VARCHAR(255) NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  direction VARCHAR(10) NOT NULL,
  bank_name VARCHAR(100),
  connection_id UUID,
  counterparty VARCHAR(100),
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Zeroed anchor rows: hardcoded accountIds acting as user/profile aggregates
-- that the dashboard loads. They must exist before any bank can be linked
-- (banking_connections.account_id FKs to accounts.id). Balance and
-- customer_name are overwritten from Enable Banking data on the first sync.
-- 1111... is the demo account the frontend's Demo mode targets (Mock ASPSP
-- connections — it's the original anchor, where mocks have historically been
-- linked); 2222... is the real account (production EB connections), so public
-- demos never show the real account's data.
INSERT INTO accounts (id, customer_name, account_type, balance, credit_limit)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'Demo', 'AGGREGATE', 0.00, 0.00),
  ('22222222-2222-2222-2222-222222222222', 'My accounts', 'AGGREGATE', 0.00, 0.00)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS banking_connections (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id),
  session_id VARCHAR(255),
  bank_name VARCHAR(100) NOT NULL,
  country VARCHAR(10) NOT NULL,
  state VARCHAR(100) NOT NULL UNIQUE,
  external_account_uid VARCHAR(255),
  account_name VARCHAR(100),
  balance NUMERIC(14,2),
  currency VARCHAR(10),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  valid_until TIMESTAMP
);
