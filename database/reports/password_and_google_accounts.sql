-- Accounts with both a password and Google (auth_provider = 'PASSWORD_AND_GOOGLE'), to decide what to do with them.
-- Read-only. Run by hand against production:
--   psql "$DATABASE_URL" -f database/reports/password_and_google_accounts.sql
--
-- Since UserService.linkGoogle (2026-11), linking Google to an account found by its email removes the password it
-- had. Accounts linked before that kept it, and that password may have been set by someone other than the owner
-- of the email ("pre-account hijacking"). They are not changed automatically.
--
-- The database cannot tell those apart from accounts that signed up with Google and set a password afterwards
-- (POST /api/users/password), which are legitimate: both end as PASSWORD_AND_GOOGLE and no history is kept. The
-- columns below only help to judge the size of the problem.

SELECT
    COUNT(*)                                                    AS password_and_google,
    COUNT(*) FILTER (WHERE refresh_token_expiry > NOW())       AS with_live_refresh_token,
    COUNT(*) FILTER (WHERE plan = 'PREMIUM')                   AS premium
FROM users
WHERE auth_provider = 'PASSWORD_AND_GOOGLE';
