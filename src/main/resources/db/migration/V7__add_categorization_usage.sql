-- Monthly allowance for automatic categorization with AI, next to the receipt scans in ai_usage.
--
-- categorized_entries counts the distinct descriptions sent to Gemini for the account in that UTC month
-- (several entries with the same description are one question). What the keyword rules and the in-memory
-- cache answer costs nothing and is not counted. Like receipt_scans, it only moves through the conditional
-- UPDATEs in AiUsageRepository, so concurrent runs cannot go over the plan's limit.
--
-- The DEFAULT matters beyond existing rows: a backend rolled back to a version that predates this file
-- creates the month's row without the column.
--
-- IF NOT EXISTS, like V4 and V5: a fresh database is built from database/schema.sql (which already has the
-- column) and baselined at version 2, so Flyway runs this file on it as well.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE ai_usage ADD COLUMN IF NOT EXISTS categorized_entries INTEGER NOT NULL DEFAULT 0;
