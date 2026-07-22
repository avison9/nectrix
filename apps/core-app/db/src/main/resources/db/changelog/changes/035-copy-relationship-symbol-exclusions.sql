--liquibase formatted sql

-- Feature — lets a Follower manually exclude specific symbols from being copied from one specific
-- Master (copy_relationships is already one row per Master<->Follower pairing, see that table's
-- own 006-copy-trading.sql comment). Deliberately an EXCLUSION list, not an allow-list: default
-- (empty array) must stay identical to today's "copy everything the Master trades" behavior, so
-- shipping this can never silently stop copying for any existing relationship. Enforced in
-- apps/copy-engine's dispatch.go, only against NEW position opens -- never against
-- close/modify/partial-close of an already-open position, so excluding a symbol mid-flight can
-- never strand an already-copied position with no way to close it.
--changeset nectrix:035-copy-relationship-symbol-exclusions
ALTER TABLE copy_relationships
    ADD COLUMN excluded_symbols TEXT[] NOT NULL DEFAULT '{}';
--rollback ALTER TABLE copy_relationships DROP COLUMN excluded_symbols;
