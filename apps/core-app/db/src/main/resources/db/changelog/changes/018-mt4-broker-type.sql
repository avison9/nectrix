--liquibase formatted sql

-- TICKET-102: MT4 adapter added alongside MT5 (pulled forward from its
-- original Phase-3 scope, TICKET-311). Both use the EA-bridge strategy in
-- apps/mt5-bridge-gateway. Widens the three existing broker_type CHECK
-- constraints (all currently 'CTRADER','MT5' only — see 004-broker-connectivity.sql,
-- 007-billing.sql) to also allow 'MT4'. Constraint names below are Postgres's
-- own auto-generated names for these unnamed inline CHECKs (confirmed via
-- \d against each table), so DROP/ADD by name is safe and exact.
--changeset nectrix:018-mt4-broker-accounts-broker-type
ALTER TABLE broker_accounts DROP CONSTRAINT broker_accounts_broker_type_check;
ALTER TABLE broker_accounts ADD CONSTRAINT broker_accounts_broker_type_check CHECK (broker_type IN ('CTRADER','MT5','MT4'));
--rollback ALTER TABLE broker_accounts DROP CONSTRAINT broker_accounts_broker_type_check;
--rollback ALTER TABLE broker_accounts ADD CONSTRAINT broker_accounts_broker_type_check CHECK (broker_type IN ('CTRADER','MT5'));

--changeset nectrix:018-mt4-broker-ib-links-broker-type
ALTER TABLE broker_ib_links DROP CONSTRAINT broker_ib_links_broker_type_check;
ALTER TABLE broker_ib_links ADD CONSTRAINT broker_ib_links_broker_type_check CHECK (broker_type IN ('CTRADER','MT5','MT4'));
--rollback ALTER TABLE broker_ib_links DROP CONSTRAINT broker_ib_links_broker_type_check;
--rollback ALTER TABLE broker_ib_links ADD CONSTRAINT broker_ib_links_broker_type_check CHECK (broker_type IN ('CTRADER','MT5'));

--changeset nectrix:018-mt4-broker-fee-reports-broker-type
ALTER TABLE broker_fee_reports DROP CONSTRAINT broker_fee_reports_broker_type_check;
ALTER TABLE broker_fee_reports ADD CONSTRAINT broker_fee_reports_broker_type_check CHECK (broker_type IN ('CTRADER','MT5','MT4'));
--rollback ALTER TABLE broker_fee_reports DROP CONSTRAINT broker_fee_reports_broker_type_check;
--rollback ALTER TABLE broker_fee_reports ADD CONSTRAINT broker_fee_reports_broker_type_check CHECK (broker_type IN ('CTRADER','MT5'));
