--liquibase formatted sql

-- TICKET-101 follow-up — broker_type (CTRADER/MT5/MT4) is the PLATFORM, not the actual broker's
-- brand name (e.g. "Pepperstone"). Both were being conflated in the UI, showing "CTRADER" where a
-- real broker name belongs. cTrader linking gets this for real from cTrader's own account list API
-- response (brokerTitleShort); MT4/MT5 linking has no equivalent OAuth account list to source it
-- from, so it's a plain user-entered field there, same trust model as display_label already has.
--changeset nectrix:031-broker-account-broker-name
ALTER TABLE broker_accounts
    ADD COLUMN broker_name TEXT;
--rollback ALTER TABLE broker_accounts DROP COLUMN broker_name;
