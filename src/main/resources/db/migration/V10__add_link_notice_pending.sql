-- users.link_notice_pending: a Google sign-in removed the account's password (UserService.linkGoogle) and the app has
-- not confirmed it told the user. While true, every Google sign-in answers passwordRemoved again, so an answer lost on
-- the way (no connection, app killed mid-request) is told by the next one. Cleared by DELETE /api/users/me/link-notice
-- and by setting a password.
--
-- NOT NULL DEFAULT FALSE: no existing account has a pending notice. A constant default does not rewrite the table on
-- PostgreSQL 11+.
--
-- IF NOT EXISTS, like V3 to V9: a fresh database is built from database/schema.sql (which already has the column)
-- and baselined at version 2, so Flyway runs this file on it as well.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS link_notice_pending BOOLEAN NOT NULL DEFAULT FALSE;
