-- users.token_version: access tokens carry the version their account had when they were issued (JWT claim "ver"),
-- and the JWT filter rejects any other. Raising it ends every session the account had: done when a Google sign-in
-- links an account whose password nobody can vouch for (UserService.linkGoogle).
--
-- NOT NULL DEFAULT 0, which tokens issued before this column existed also count as, so nobody is signed out by the
-- deploy. Adding a column with a constant default does not rewrite the table on PostgreSQL 11+.
--
-- IF NOT EXISTS, like V3 to V8: a fresh database is built from database/schema.sql (which already has the column)
-- and baselined at version 2, so Flyway runs this file on it as well.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
