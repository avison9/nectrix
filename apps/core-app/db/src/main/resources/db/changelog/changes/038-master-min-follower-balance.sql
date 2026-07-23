--liquibase formatted sql

-- Feature — lets a Master set a floor on how much capital a Follower needs before they're allowed
-- to start copying that Master's strategy. NULL (the default) means no minimum, unchanged behavior
-- for every existing profile. Enforced at copy-relationship activation time (accept-invite / admin
-- manual-link), not an ongoing runtime check — see InvitationCopySetupService/AdminCopyLinkService.
--changeset nectrix:038-master-min-follower-balance
ALTER TABLE master_profiles ADD COLUMN min_follower_balance NUMERIC(20,2);
--rollback ALTER TABLE master_profiles DROP COLUMN min_follower_balance;
