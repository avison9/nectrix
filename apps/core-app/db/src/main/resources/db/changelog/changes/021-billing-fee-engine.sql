--liquibase formatted sql

-- TICKET-113: docs/11-fee-engine-billing.md §11.3 explicitly calls out that deposits/withdrawals
-- are "effectively a 4th implicit reason and should be added to that enum in implementation" for
-- full traceability. Constraint name is Postgres's own auto-generated name for this unnamed
-- inline CHECK (confirmed via \d against the table), so DROP/ADD by name is safe and exact --
-- same convention 018-mt4-broker-type.sql already established for widening a CHECK constraint.
--changeset nectrix:021-hwm-history-deposit-withdrawal-reasons
ALTER TABLE high_water_mark_history DROP CONSTRAINT high_water_mark_history_reason_check;
ALTER TABLE high_water_mark_history ADD CONSTRAINT high_water_mark_history_reason_check
    CHECK (reason IN ('NEW_EQUITY_HIGH','RESET_ON_PAYOUT','ADMIN_ADJUSTMENT','DEPOSIT_ADJUSTMENT','WITHDRAWAL_ADJUSTMENT'));
--rollback ALTER TABLE high_water_mark_history DROP CONSTRAINT high_water_mark_history_reason_check;
--rollback ALTER TABLE high_water_mark_history ADD CONSTRAINT high_water_mark_history_reason_check CHECK (reason IN ('NEW_EQUITY_HIGH','RESET_ON_PAYOUT','ADMIN_ADJUSTMENT'));

-- TICKET-113: no Stripe customer reference exists anywhere yet (not on users, not elsewhere) --
-- needed to charge a Follower for a STRIPE_INVOICE-collected performance fee independent of any
-- subscription. Nullable: most users will never be a paying Follower on this collection method.
--changeset nectrix:021-users-stripe-customer-id
ALTER TABLE users ADD COLUMN stripe_customer_id TEXT;
--rollback ALTER TABLE users DROP COLUMN stripe_customer_id;
