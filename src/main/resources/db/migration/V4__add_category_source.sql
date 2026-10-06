-- Where an entry's category came from: the user (or a receipt the user scanned) chose it, or the
-- server is assigning / has assigned it automatically from the description.
--
-- NOT NULL DEFAULT 'USER' on purpose: every row that exists today was categorised by a person, and
-- automatic categorisation must never overwrite one. Only entries created without a category by
-- an account that turned AI processing on are ever stored as 'AUTO'.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE budget_entries
    ADD COLUMN IF NOT EXISTS category_source VARCHAR(10) NOT NULL DEFAULT 'USER'
        CHECK (category_source IN ('USER', 'AUTO'));
