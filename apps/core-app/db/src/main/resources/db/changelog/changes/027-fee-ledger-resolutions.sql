--liquibase formatted sql

-- TICKET-117: dispute resolution. performance_fee_ledger's own status CHECK constraint already
-- includes DISPUTED (007-billing.sql) -- no change needed there. Resolving a dispute must never
-- silently mutate the original computation_detail/amounts (that record is deliberately
-- self-contained so a dispute can reconstruct the calculation, see SettlementComputation's own
-- Javadoc) -- this table is the compensating-record pattern high_water_mark_history already
-- establishes for the same reason: append-only, paired with a status transition, never an
-- overwrite.
--changeset nectrix:027-fee-ledger-resolutions
CREATE TABLE fee_ledger_resolutions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_id            UUID NOT NULL REFERENCES performance_fee_ledger(id),
    resolution           TEXT NOT NULL CHECK (resolution IN ('UPHOLD','ADJUST','VOID')),
    note                 TEXT,
    adjusted_amount      NUMERIC(20,4),
    resolved_by_user_id  UUID NOT NULL REFERENCES users(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fee_ledger_resolutions_ledger ON fee_ledger_resolutions(ledger_id);
--rollback DROP TABLE IF EXISTS fee_ledger_resolutions CASCADE;
