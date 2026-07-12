-- Idempotent: seeds an ACTIVE mock bank connection so the dashboard renders
-- without going through the Enable Banking OAuth flow.
INSERT INTO banking_connections
  (id, account_id, bank_name, country, state, status, session_id, external_account_uid)
VALUES
  (gen_random_uuid(),
   '11111111-1111-1111-1111-111111111111',
   'N26', 'DE', 'seed-demo-mock', 'ACTIVE',
   'mock-session-vm', 'mock-account-vm')
ON CONFLICT (state) DO NOTHING;

SELECT bank_name, country, status FROM banking_connections WHERE status = 'ACTIVE';
