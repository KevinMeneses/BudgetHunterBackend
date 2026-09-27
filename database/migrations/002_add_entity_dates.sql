-- Entity dates: let budgets and budget entries carry the user-facing calendar date the app
-- shows for them, instead of only the audit timestamps (creation_date/modification_date),
-- which drift to server time and are not what the user actually picked.
--
-- schema.sql is mounted at /docker-entrypoint-initdb.d/ and only runs against an empty data
-- volume, so it never reaches a database that already has rows. Run this by hand instead:
--
--   docker compose exec -T postgres psql -U budgethunter_user -d budgethunter \
--     < database/migrations/002_add_entity_dates.sql
--
-- Run it BEFORE deploying the new jar. Production uses spring.jpa.hibernate.ddl-auto=validate,
-- so a backend that starts against the old schema fails validation and restart-loops.
--
-- About existing rows. Entries are left NULL: the app derives their date exactly from the
-- creation_date the response already carries, so there is nothing to guess here. Budgets have no
-- timestamp of any kind on this side, so a client installing fresh would show today's date for
-- every old budget - the very bug this change fixes. They get the date of their earliest entry
-- instead, which is a deliberate approximation: the day a budget was really created is recorded
-- nowhere server-side. Budgets with no entries stay NULL and fall back to whatever date the
-- client already holds. No time-zone arithmetic below on purpose - the container's time zone is
-- not pinned in this repo, so converting could shift correct values by a day.

BEGIN;

ALTER TABLE budgets ADD COLUMN IF NOT EXISTS date DATE;
ALTER TABLE budget_entries ADD COLUMN IF NOT EXISTS date DATE;

UPDATE budgets b
SET date = (
    SELECT MIN(be.creation_date)::date
    FROM budget_entries be
    WHERE be.budget_id = b.id
)
WHERE b.date IS NULL;

COMMIT;
