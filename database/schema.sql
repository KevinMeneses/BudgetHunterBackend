-- BudgetHunter Database Schema for PostgreSQL
-- This script creates the database schema for production deployment
-- Generated based on JPA entities in the application
--
-- This is the BASELINE, and docker-entrypoint-initdb.d only runs it on an empty volume. Every
-- change after version 2 belongs in src/main/resources/db/migration as V3__, V4__ ... - Flyway
-- applies those on startup (see application-production.properties). Keep this file in step with
-- them, because a fresh database is built from here and then baselined, not migrated up from V1.

-- ============================================
-- DATABASE CREATION
-- ============================================
-- Run this as a PostgreSQL superuser (e.g., postgres)
-- CREATE DATABASE budgethunter;
-- CREATE USER budgethunter_user WITH PASSWORD 'your_secure_password_here';
-- GRANT ALL PRIVILEGES ON DATABASE budgethunter TO budgethunter_user;

-- Connect to the budgethunter database before running the rest of this script
-- \c budgethunter

-- ============================================
-- SCHEMA DEFINITION
-- ============================================

-- Users table
CREATE TABLE IF NOT EXISTS users (
    email VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    -- Nullable: an account created through Google sign-in never chose a password.
    password VARCHAR(255),
    google_subject VARCHAR(255) UNIQUE,
    auth_provider VARCHAR(32) NOT NULL DEFAULT 'PASSWORD',
    refresh_token VARCHAR(500) UNIQUE,
    refresh_token_expiry TIMESTAMP,
    -- App preferences. NULL = never saved, so a client can tell a fresh account from a chosen value.
    sms_reading_enabled BOOLEAN,
    ai_processing_enabled BOOLEAN,
    default_budget_id BIGINT,
    selected_bank_ids VARCHAR(1000)
);

-- Budgets table
CREATE TABLE IF NOT EXISTS budgets (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    -- Nullable: the user-facing calendar date the app shows for this budget. NULL means it
    -- predates this column and no local date is known for it yet.
    date DATE
);

-- User-Budget junction table (many-to-many relationship)
CREATE TABLE IF NOT EXISTS user_budgets (
    budget_id BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    PRIMARY KEY (budget_id, user_email),
    CONSTRAINT fk_user_budgets_budget FOREIGN KEY (budget_id) REFERENCES budgets(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_budgets_user FOREIGN KEY (user_email) REFERENCES users(email) ON DELETE CASCADE
);

-- Budget entries table
CREATE TABLE IF NOT EXISTS budget_entries (
    id BIGSERIAL PRIMARY KEY,
    budget_id BIGINT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    description VARCHAR(255) NOT NULL,
    category VARCHAR(255) NOT NULL,
    -- Who chose the category: USER (a person, or a scanned receipt) or AUTO (assigned by the server
    -- from the description). Existing rows are USER; automatic categorisation never overwrites USER.
    category_source VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (category_source IN ('USER', 'AUTO')),
    type VARCHAR(50) NOT NULL CHECK (type IN ('INCOME', 'OUTCOME')),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    creation_date TIMESTAMP NOT NULL,
    modification_date TIMESTAMP NOT NULL,
    -- Nullable: the user-facing calendar date the app shows for this entry. NULL means it
    -- predates this column and no local date is known for it yet.
    date DATE,
    CONSTRAINT fk_budget_entries_budget FOREIGN KEY (budget_id) REFERENCES budgets(id) ON DELETE CASCADE,
    CONSTRAINT fk_budget_entries_created_by FOREIGN KEY (created_by) REFERENCES users(email) ON DELETE SET NULL,
    CONSTRAINT fk_budget_entries_updated_by FOREIGN KEY (updated_by) REFERENCES users(email) ON DELETE SET NULL
);

-- ============================================
-- INDEXES FOR PERFORMANCE
-- ============================================

-- Index on users for refresh token lookup
CREATE INDEX IF NOT EXISTS idx_users_refresh_token ON users(refresh_token) WHERE refresh_token IS NOT NULL;

-- Index on users for Google sign-in lookup
CREATE INDEX IF NOT EXISTS idx_users_google_subject ON users(google_subject) WHERE google_subject IS NOT NULL;

-- Index on user_budgets for faster user lookup
CREATE INDEX IF NOT EXISTS idx_user_budgets_user_email ON user_budgets(user_email);
CREATE INDEX IF NOT EXISTS idx_user_budgets_budget_id ON user_budgets(budget_id);

-- Indexes on budget_entries for faster queries
CREATE INDEX IF NOT EXISTS idx_budget_entries_budget_id ON budget_entries(budget_id);
CREATE INDEX IF NOT EXISTS idx_budget_entries_modification_date ON budget_entries(modification_date);
CREATE INDEX IF NOT EXISTS idx_budget_entries_creation_date ON budget_entries(creation_date);
CREATE INDEX IF NOT EXISTS idx_budget_entries_type ON budget_entries(type);
CREATE INDEX IF NOT EXISTS idx_budget_entries_category ON budget_entries(category);

-- Composite index for common query patterns
CREATE INDEX IF NOT EXISTS idx_budget_entries_budget_type ON budget_entries(budget_id, type);

-- ============================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================

COMMENT ON TABLE users IS 'Application users with email-based authentication and JWT refresh tokens';
COMMENT ON TABLE budgets IS 'Budgets that can be shared among multiple users';
COMMENT ON TABLE user_budgets IS 'Junction table for many-to-many relationship between users and budgets';
COMMENT ON TABLE budget_entries IS 'Income and outcome entries within budgets with audit trail';

COMMENT ON COLUMN users.password IS 'BCrypt hash; NULL for accounts that only sign in with Google';
COMMENT ON COLUMN users.google_subject IS 'Google "sub" claim - immutable account id, unlike the email';
COMMENT ON COLUMN users.auth_provider IS 'How the account can authenticate: PASSWORD, GOOGLE or PASSWORD_AND_GOOGLE';
COMMENT ON COLUMN users.refresh_token IS 'JWT refresh token for authentication renewal';
COMMENT ON COLUMN users.refresh_token_expiry IS 'Expiration timestamp for the refresh token';
COMMENT ON COLUMN budget_entries.type IS 'Entry type: INCOME or OUTCOME';
COMMENT ON COLUMN budget_entries.created_by IS 'User email who created this entry (nullable for audit trail)';
COMMENT ON COLUMN budget_entries.updated_by IS 'User email who last updated this entry (nullable for audit trail)';
COMMENT ON COLUMN budgets.date IS 'User-facing calendar date shown in the app; NULL for rows that predate this column';
COMMENT ON COLUMN budget_entries.date IS 'User-facing calendar date shown in the app; NULL for rows that predate this column';
