-- Monetization: what each account paid for, and how much paid AI it used each month.
--
-- users.plan is FREE for everyone until a store purchase (RevenueCat) says otherwise.
-- ai_usage holds one row per account and UTC calendar month ('YYYY-MM'); a new month is a new
-- row, so nothing ever has to be reset. The counter only moves through conditional UPDATEs
-- (AiUsageRepository), which keep concurrent requests from going over the limit.
--
-- IF NOT EXISTS everywhere: a fresh database is built from database/schema.sql (which already
-- has all of this) and baselined at version 2, so Flyway runs this file on it as well.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS plan VARCHAR(20) NOT NULL DEFAULT 'FREE';

CREATE TABLE IF NOT EXISTS ai_usage (
    user_email VARCHAR(255) NOT NULL REFERENCES users(email) ON DELETE CASCADE,
    period VARCHAR(7) NOT NULL,
    receipt_scans INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_email, period)
);
