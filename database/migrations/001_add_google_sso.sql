-- Google single sign-on: allow password-less accounts and track the Google identity.
--
-- schema.sql is mounted at /docker-entrypoint-initdb.d/ and only runs against an empty data
-- volume, so it never reaches a database that already has rows. Run this by hand instead:
--
--   docker compose exec -T postgres psql -U budgethunter_user -d budgethunter \
--     < database/migrations/001_add_google_sso.sql
--
-- Run it BEFORE deploying the new jar. Production uses spring.jpa.hibernate.ddl-auto=validate,
-- so a backend that starts against the old schema fails validation and restart-loops.

BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS google_subject VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(32) NOT NULL DEFAULT 'PASSWORD';
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- A unique index ignores NULLs in Postgres, so every existing password-only account is fine.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_google_subject ON users(google_subject);

COMMIT;
