-- Per-account app preferences (the settings screen): SMS reading, default budget, banks to
-- listen to for SMS notifications, and AI receipt processing.
--
-- Every column is nullable on purpose. NULL means "this account never saved a value", which lets
-- a client tell a fresh account (upload what the device already has) apart from one that chose
-- something (adopt it). A default here would erase that distinction.
--
-- default_budget_id carries no foreign key: it is a preference, not data a budget deletion
-- should cascade into. The service drops it on read once the user can no longer reach the budget.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE users ADD COLUMN IF NOT EXISTS sms_reading_enabled BOOLEAN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS ai_processing_enabled BOOLEAN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS default_budget_id BIGINT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS selected_bank_ids VARCHAR(1000);
