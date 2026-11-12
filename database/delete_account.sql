-- Deletes one account by hand, for when it cannot be done from the app (DELETE /api/users/me,
-- AccountDeletionService), with the same result. Run it instead of a bare DELETE FROM users, which the foreign
-- key on budgets.owner_email refuses while the account still owns budgets:
--
--   psql "$DATABASE_URL" -v email=someone@example.com -f database/delete_account.sql
--
-- Every budget the account owns goes to another member, with the same rule as V6__add_budget_owner.sql and
-- BudgetService.handOverOwnedBudgets (the member who created its oldest entry, else the lowest email); one
-- with no other member is deleted with its entries. Then the account goes, and with it (ON DELETE CASCADE)
-- its memberships and AI usage; the entries it wrote stay, with created_by/updated_by set to NULL.
-- All or nothing. Keep it in step with AccountDeletionService.

\set ON_ERROR_STOP on
BEGIN;

UPDATE budgets
SET owner_email = COALESCE(
    (
        SELECT be.created_by
        FROM budget_entries be
        JOIN user_budgets ub ON ub.budget_id = be.budget_id AND ub.user_email = be.created_by
        WHERE be.budget_id = budgets.id AND be.created_by <> :'email'
        ORDER BY be.creation_date, be.id
        LIMIT 1
    ),
    (SELECT MIN(ub.user_email) FROM user_budgets ub WHERE ub.budget_id = budgets.id AND ub.user_email <> :'email')
)
WHERE owner_email = :'email'
  AND EXISTS (SELECT 1 FROM user_budgets ub WHERE ub.budget_id = budgets.id AND ub.user_email <> :'email');

-- What is still theirs has nobody else in it.
DELETE FROM budget_entries WHERE budget_id IN (SELECT id FROM budgets WHERE owner_email = :'email');
DELETE FROM user_budgets WHERE budget_id IN (SELECT id FROM budgets WHERE owner_email = :'email');
DELETE FROM budgets WHERE owner_email = :'email';

DELETE FROM users WHERE email = :'email';

COMMIT;
