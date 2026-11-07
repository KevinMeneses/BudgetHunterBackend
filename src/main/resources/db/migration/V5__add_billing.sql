-- Store purchases (RevenueCat): the id each account has there, and until when PREMIUM holds.
--
-- billing_id is RevenueCat's "app user id" for the account: random, so the email never reaches the stores
-- or RevenueCat. Existing accounts get one here; new ones get it from the application when they are created.
-- plan_expires_at is the end of the paid period (grace period included) as RevenueCat reports it; NULL with
-- PREMIUM means it does not run out.
--
-- IF NOT EXISTS everywhere: a fresh database is built from database/schema.sql (which already has all of
-- this) and baselined at version 2, so Flyway runs this file on it as well. gen_random_uuid() is built into
-- PostgreSQL 13+.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS billing_id VARCHAR(36);
UPDATE users SET billing_id = gen_random_uuid()::text WHERE billing_id IS NULL;
ALTER TABLE users ALTER COLUMN billing_id SET NOT NULL;
-- For rows written outside the application (the application always sets it itself).
ALTER TABLE users ALTER COLUMN billing_id SET DEFAULT gen_random_uuid()::text;
-- Same name PostgreSQL gives the UNIQUE constraint in schema.sql, so a fresh database skips this.
CREATE UNIQUE INDEX IF NOT EXISTS users_billing_id_key ON users (billing_id);

ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_expires_at TIMESTAMP WITH TIME ZONE;
