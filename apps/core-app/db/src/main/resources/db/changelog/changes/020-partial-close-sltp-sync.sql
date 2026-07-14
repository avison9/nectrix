--liquibase formatted sql

-- TICKET-107: copied_trades (006-copy-trading.sql) tracks computed_volume_lots
-- (the ORIGINAL volume computed at open time) but nothing tracks the
-- follower's CURRENT remaining open volume as it shrinks across sequential
-- partial closes. This is a real correctness requirement, not a nice-to-have:
-- docs/09-money-management-risk-formulas.md's own §9.5 partial-close ratio
-- math only preserves proportionality across MULTIPLE sequential partial
-- closes when each one's ratio is applied against the CURRENT open volume,
-- not the immutable original -- see apps/copy-engine/internal/pipeline/
-- dispatch.go's handlePartialClose doc comment for the full worked example.
-- This same column also answers "is this position now fully closed".
--changeset nectrix:020-copied-trades-current-open-volume
ALTER TABLE copied_trades ADD COLUMN current_open_volume_lots NUMERIC(12,4) NOT NULL DEFAULT 0;
-- Defensive backfill for any FILLED/PARTIALLY_CLOSED row that predates this
-- column -- no real deployed data exists yet (pre-launch MVP), but this is
-- cheap and correct regardless of what's already in any given environment's
-- database by the time this migration runs.
UPDATE copied_trades SET current_open_volume_lots = computed_volume_lots WHERE status IN ('FILLED','PARTIALLY_CLOSED');
--rollback ALTER TABLE copied_trades DROP COLUMN IF EXISTS current_open_volume_lots;

-- TICKET-107 / FR-3.7 ("Allow a follower to set independent SL/TP/max-risk
-- overrides that take precedence over blind mirroring"): docs/08-copy-
-- trading-engine.md §8.7 requires honoring a follower's pinned SL/TP, but
-- risk_profiles (006-copy-trading.sql) has no column for it -- the same
-- design-doc-level gap already flagged for AllowAssumedSLDistanceFallback
-- (TICKET-104), not just an implementation lag. Scoped narrowly to just the
-- SL/TP-pin behavior this ticket's AC4 requires, not FR-3.7's broader
-- max-risk-override surface. Defaults FALSE so every existing relationship
-- keeps today's blind-mirroring behavior unchanged; the follower-facing UI/
-- API to actually set this to TRUE is Core App/Java territory, out of scope
-- here.
--changeset nectrix:020-risk-profiles-pin-follower-sltp
ALTER TABLE risk_profiles ADD COLUMN pin_follower_sl_tp BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE risk_profiles DROP COLUMN IF EXISTS pin_follower_sl_tp;
