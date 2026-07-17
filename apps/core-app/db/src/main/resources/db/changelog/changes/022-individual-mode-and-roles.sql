--liquibase formatted sql

-- TICKET-114: the account model now distinguishes 3 trading *modes* (Master/Follower/Individual)
-- from a flatter role catalog. SUPER_ADMIN/USER are new; MASTER/FOLLOWER keep working exactly as
-- they do today (still Admin-invite / Master-invite additive role grants) -- this just adds the
-- missing base role every account should have, and the not-yet-wired-into-any-check top admin tier
-- TICKET-115 (account tier-change approval) will need.
--changeset nectrix:022-seed-super-admin-and-user-roles
INSERT INTO roles (name) VALUES ('SUPER_ADMIN'), ('USER');
--rollback DELETE FROM roles WHERE name IN ('SUPER_ADMIN','USER');

-- TICKET-114: an Individual-mode user's private main->slave copying (broadcast restricted to their
-- own broker_accounts, never another platform user's) is neither invite-created nor
-- follow-request-created -- chk_exactly_one_origin as it stands would reject it outright. Widened
-- to a 3rd option rather than relaxed to "at most one", so a row missing all 3 origin markers
-- (a data-entry bug, not a real path) still fails loudly.
--changeset nectrix:022-copy-relationships-individual-origin
ALTER TABLE copy_relationships ADD COLUMN originating_individual_setup BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE copy_relationships DROP CONSTRAINT chk_exactly_one_origin;
ALTER TABLE copy_relationships ADD CONSTRAINT chk_exactly_one_origin CHECK (
    (originating_invitation_id IS NOT NULL)::int
    + (originating_follow_request_id IS NOT NULL)::int
    + originating_individual_setup::int = 1
);
--rollback ALTER TABLE copy_relationships DROP CONSTRAINT chk_exactly_one_origin;
--rollback ALTER TABLE copy_relationships ADD CONSTRAINT chk_exactly_one_origin CHECK ((originating_invitation_id IS NOT NULL)::int + (originating_follow_request_id IS NOT NULL)::int = 1);
--rollback ALTER TABLE copy_relationships DROP COLUMN originating_individual_setup;
