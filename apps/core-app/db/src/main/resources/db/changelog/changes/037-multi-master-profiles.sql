--liquibase formatted sql

-- TICKET-125 — a user with multiple MASTER_ONLY/BOTH broker accounts (e.g. running several
-- independent strategies) previously could only ever have ONE followable master_profiles row
-- (user_id was UNIQUE), even though broker_accounts itself has never restricted a user to one
-- account. Dropping the per-user uniqueness lets a user create one profile per broker account.
-- primary_broker_account_id becomes the real per-profile identity key instead — genuinely unique
-- now (it wasn't enforced at the schema level before; MasterProfileRepository's own
-- findByPrimaryBrokerAccountId Javadoc already flagged this as "unique in practice, not
-- enforced" — this changeset makes it a real constraint).
--changeset nectrix:037-master-profiles-per-account
ALTER TABLE master_profiles DROP CONSTRAINT master_profiles_user_id_key;
ALTER TABLE master_profiles ADD CONSTRAINT master_profiles_primary_broker_account_id_key UNIQUE (primary_broker_account_id);
--rollback ALTER TABLE master_profiles DROP CONSTRAINT master_profiles_primary_broker_account_id_key;
--rollback ALTER TABLE master_profiles ADD CONSTRAINT master_profiles_user_id_key UNIQUE (user_id);
