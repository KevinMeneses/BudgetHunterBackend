-- Which version of the privacy policy (/privacidad) each account accepted, and when: the proof of the user's
-- authorization that Ley 1581 de 2012 asks for. The version is the date of the policy's "Última actualización"
-- ('YYYY-MM-DD', com.budgethunter.privacy.PRIVACY_POLICY_VERSION); the date is the server's, never the device's.
--
-- Both nullable with no default: the accounts that exist now never accepted any version, and NULL is what tells
-- the app to ask them (GET /api/users/me, privacyPolicy). A default would record an acceptance nobody gave.
-- Deleting the account deletes them with its row; nothing else points at them.
--
-- IF NOT EXISTS, like V3 to V7: a fresh database is built from database/schema.sql (which already has the
-- columns) and baselined at version 2, so Flyway runs this file on it as well.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS privacy_policy_version VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS privacy_policy_accepted_at TIMESTAMP WITH TIME ZONE;
