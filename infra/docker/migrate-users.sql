-- Idempotent migration for the `users` table introduced by the GitHub OAuth
-- login flow. Run against deployed databases whose persistent volume predates
-- init.sql's users block:
--
--   docker compose exec -T database psql -U bank -d bankdb < infra/docker/migrate-users.sql
--   # or in k8s:
--   kubectl -n devops26 exec -i deploy/postgres -- psql -U bank -d bankdb < infra/docker/migrate-users.sql

CREATE TABLE IF NOT EXISTS users (
  github_id BIGINT PRIMARY KEY,
  login VARCHAR(100) NOT NULL,
  first_name VARCHAR(100),
  last_name VARCHAR(100),
  email VARCHAR(255),
  avatar_url VARCHAR(500),
  account_id UUID REFERENCES accounts(id),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Add the per-user aggregate account link for DBs created before this column existed.
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES accounts(id);

SELECT github_id, login, first_name, last_name, email, account_id FROM users;
