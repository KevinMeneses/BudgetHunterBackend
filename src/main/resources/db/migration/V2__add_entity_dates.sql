-- Entity dates: let budgets and budget entries carry the user-facing calendar date the app
-- shows for them, instead of only the audit timestamps (creation_date/modification_date),
-- which drift to server time and are not what the user actually picked.
--
-- Pre-baseline history, like V1: this ran by hand against production on 2026-09-26, so the
-- baseline sits at version 2 and Flyway never replays it. Anything new starts at V3.
--
-- About existing rows. Entries were left NULL: the app derives their date exactly from the
-- creation_date the response already carries, so there was nothing to guess. Budgets have no
-- timestamp of any kind on this side, so a client installing fresh would show today's date for
-- every old budget - the very bug this change fixed. They got the date of their earliest entry
-- instead, a deliberate approximation, since the day a budget was really created is recorded
-- nowhere server-side. Budgets with no entries stayed NULL and fall back to whatever date the
-- client already holds. No time-zone arithmetic below on purpose - the container's time zone is
-- not pinned in this repo, so converting could shift correct values by a day.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE budgets ADD COLUMN IF NOT EXISTS date DATE;
ALTER TABLE budget_entries ADD COLUMN IF NOT EXISTS date DATE;

UPDATE budgets b
SET date = (
    SELECT MIN(be.creation_date)::date
    FROM budget_entries be
    WHERE be.budget_id = b.id
)
WHERE b.date IS NULL;
