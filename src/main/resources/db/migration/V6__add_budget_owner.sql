-- Budget ownership: budgets.owner_email is the member who may delete the budget for everyone and remove
-- other members; everyone else can only leave. New budgets get their creator from the application.
--
-- Existing budgets: user_budgets records neither when nor in what order members joined, so their creator
-- is unknown and the owner below is a best guess, picked in this order:
--   1. a single member: that member;
--   2. otherwise the member who created the budget's oldest entry (creation_date, then id), only counting
--      entries whose creator is still a member;
--   3. otherwise (no entries by current members) the lowest email, so the choice is deterministic.
-- Rule 1 needs no statement of its own: with one member, 2 and 3 can only pick that member.
-- Budgets with no member at all (left behind when the last member's account was deleted) are unreachable
-- through the API and nobody could ever own them, so they are deleted with their entries.
-- database/reports/budget_owner_preview.sql counts how many budgets fall under each rule; run it before
-- deploying.
--
-- The foreign key has no ON DELETE action on purpose: deleting an account that still owns budgets fails
-- instead of leaving them without an owner. Hand them over first: database/delete_account.sql does it by hand
-- (the API cannot delete accounts yet), BudgetService.handOverOwnedBudgets from the application.
--
-- Re-runnable, like V5: a fresh database built from database/schema.sql already has all of this and is
-- baselined at version 2, so Flyway runs this file on it as well. Plain SQL that H2 also accepts, so
-- BudgetOwnerMigrationTest can run this very file.
--
-- No BEGIN/COMMIT: Flyway owns the transaction per migration.

ALTER TABLE budgets ADD COLUMN IF NOT EXISTS owner_email VARCHAR(255);

DELETE FROM budget_entries
WHERE budget_id IN (
    SELECT b.id FROM budgets b
    WHERE NOT EXISTS (SELECT 1 FROM user_budgets ub WHERE ub.budget_id = b.id)
);

DELETE FROM budgets
WHERE NOT EXISTS (SELECT 1 FROM user_budgets ub WHERE ub.budget_id = budgets.id);

UPDATE budgets
SET owner_email = COALESCE(
    (
        SELECT be.created_by
        FROM budget_entries be
        JOIN user_budgets ub ON ub.budget_id = be.budget_id AND ub.user_email = be.created_by
        WHERE be.budget_id = budgets.id
        ORDER BY be.creation_date, be.id
        LIMIT 1
    ),
    (SELECT MIN(ub.user_email) FROM user_budgets ub WHERE ub.budget_id = budgets.id)
)
WHERE owner_email IS NULL;

ALTER TABLE budgets ALTER COLUMN owner_email SET NOT NULL;

ALTER TABLE budgets DROP CONSTRAINT IF EXISTS fk_budgets_owner;
ALTER TABLE budgets ADD CONSTRAINT fk_budgets_owner FOREIGN KEY (owner_email) REFERENCES users (email);

CREATE INDEX IF NOT EXISTS idx_budgets_owner_email ON budgets (owner_email);
