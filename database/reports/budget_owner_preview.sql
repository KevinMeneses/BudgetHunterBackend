-- Read-only. Run against production BEFORE deploying V6__add_budget_owner.sql: how many existing budgets get
-- their owner from each of the migration's rules, and how many it deletes for having no member at all.
--
--   psql "$DATABASE_URL" -f database/reports/budget_owner_preview.sql
--
-- Same rules, in the same order, as the migration. Once V6 has run, budgets.owner_email exists and this
-- query is no longer meaningful.

SELECT rule, COUNT(*) AS budgets
FROM (
    SELECT
        CASE
            WHEN members = 0 THEN '0. no members: deleted'
            WHEN members = 1 THEN '1. single member'
            WHEN has_member_entries THEN '2. creator of the oldest entry'
            ELSE '3. lowest email'
        END AS rule
    FROM (
        SELECT
            b.id,
            (SELECT COUNT(*) FROM user_budgets ub WHERE ub.budget_id = b.id) AS members,
            EXISTS (
                SELECT 1
                FROM budget_entries be
                JOIN user_budgets ub ON ub.budget_id = be.budget_id AND ub.user_email = be.created_by
                WHERE be.budget_id = b.id
            ) AS has_member_entries
        FROM budgets b
    ) per_budget
) classified
GROUP BY rule
ORDER BY rule;
