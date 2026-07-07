--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Deferred foreign keys" — forward references
-- from 003-invitations-onboarding.sql and 004-broker-connectivity.sql, now
-- addable since master_profiles/broker_ib_links/money_management_profiles/
-- risk_profiles/broker_accounts all exist. Grouped one entry per originating
-- table, except the users<->invitations pair below: that one is the single
-- genuine circular reference in the domain (invitations.created_by_user_id
-- already points at users; this closes the cycle back), so it gets its own
-- entry, separate from the rest of invitations' FKs.
--changeset nectrix:011-invitations-fks
ALTER TABLE invitations ADD CONSTRAINT fk_invitations_master       FOREIGN KEY (master_profile_id) REFERENCES master_profiles(id) ON DELETE CASCADE;
ALTER TABLE invitations ADD CONSTRAINT fk_invitations_ib_link      FOREIGN KEY (suggested_broker_ib_link_id) REFERENCES broker_ib_links(id);
ALTER TABLE invitations ADD CONSTRAINT fk_invitations_mm_profile   FOREIGN KEY (suggested_money_management_profile_id) REFERENCES money_management_profiles(id);
ALTER TABLE invitations ADD CONSTRAINT fk_invitations_risk_profile FOREIGN KEY (suggested_risk_profile_id) REFERENCES risk_profiles(id);
--rollback ALTER TABLE invitations DROP CONSTRAINT fk_invitations_risk_profile;
--rollback ALTER TABLE invitations DROP CONSTRAINT fk_invitations_mm_profile;
--rollback ALTER TABLE invitations DROP CONSTRAINT fk_invitations_ib_link;
--rollback ALTER TABLE invitations DROP CONSTRAINT fk_invitations_master;

--changeset nectrix:011-users-invitation-fk
ALTER TABLE users ADD CONSTRAINT fk_users_created_via_invitation FOREIGN KEY (created_via_invitation_id) REFERENCES invitations(id);
--rollback ALTER TABLE users DROP CONSTRAINT fk_users_created_via_invitation;

--changeset nectrix:011-follow-requests-fks
ALTER TABLE follow_requests ADD CONSTRAINT fk_followrequests_master     FOREIGN KEY (master_profile_id) REFERENCES master_profiles(id);
ALTER TABLE follow_requests ADD CONSTRAINT fk_followrequests_broker_acc FOREIGN KEY (follower_broker_account_id) REFERENCES broker_accounts(id);
ALTER TABLE follow_requests ADD CONSTRAINT fk_followrequests_mm_profile FOREIGN KEY (proposed_money_management_profile_id) REFERENCES money_management_profiles(id);
ALTER TABLE follow_requests ADD CONSTRAINT fk_followrequests_risk_prof  FOREIGN KEY (proposed_risk_profile_id) REFERENCES risk_profiles(id);
--rollback ALTER TABLE follow_requests DROP CONSTRAINT fk_followrequests_risk_prof;
--rollback ALTER TABLE follow_requests DROP CONSTRAINT fk_followrequests_mm_profile;
--rollback ALTER TABLE follow_requests DROP CONSTRAINT fk_followrequests_broker_acc;
--rollback ALTER TABLE follow_requests DROP CONSTRAINT fk_followrequests_master;

--changeset nectrix:011-broker-ib-links-fk
ALTER TABLE broker_ib_links ADD CONSTRAINT fk_broker_ib_links_master FOREIGN KEY (master_profile_id) REFERENCES master_profiles(id) ON DELETE CASCADE;
--rollback ALTER TABLE broker_ib_links DROP CONSTRAINT fk_broker_ib_links_master;
