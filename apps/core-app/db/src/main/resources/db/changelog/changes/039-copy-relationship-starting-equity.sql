--liquibase formatted sql

-- Feature — captures the follower broker account's live equity at the moment a copy relationship
-- is actually activated (accept-invite / admin manual-link), so "% return since following" can be
-- computed later without ever needing the follower's raw balance/equity value itself. NULL for
-- every relationship created before this shipped — account_snapshots has no relationship-scoping
-- column and its own backfill job (AccountSnapshotSchedulerJob) is disabled in the real deployment,
-- so reconstructing a starting point for old rows would be unreliable; those simply show no
-- return% rather than a fabricated one.
--changeset nectrix:039-copy-relationship-starting-equity
ALTER TABLE copy_relationships ADD COLUMN starting_equity NUMERIC(20,4);
--rollback ALTER TABLE copy_relationships DROP COLUMN starting_equity;
