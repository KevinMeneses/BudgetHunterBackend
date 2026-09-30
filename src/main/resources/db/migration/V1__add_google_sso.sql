-- Google single sign-on: allow password-less accounts and track the Google identity.
--
-- Pre-baseline history. This one ran by hand against production in September 2026, before Flyway
-- existed here, so the baseline in application-production.properties sits at version 2 and Flyway
-- never replays it. It is kept so the numbering stays continuous and so the next person reading
-- db/migration can see how the schema actually got here.
--
-- No BEGIN/COMMIT: Flyway runs each migration in its own transaction, and taking that control
-- away from it is what breaks the all-or-nothing guarantee for everything below.

ALTER TABLE users ADD COLUMN IF NOT EXISTS google_subject VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(32) NOT NULL DEFAULT 'PASSWORD';
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- A unique index ignores NULLs in Postgres, so every existing password-only account is fine.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_google_subject ON users(google_subject);
