--liquibase formatted sql

-- Admin manual follower->master linking: a SUPER_ADMIN/ADMIN can create a real
-- copy_relationships row directly, bypassing invite-send/invite-accept and follow-request
-- entirely. Same widening precedent 022-individual-mode-and-roles.sql already established
-- for originating_individual_setup -- a 4th origin, never "at most one" relaxed, so a row
-- missing all 4 origin markers still fails loudly.
--changeset nectrix:036-copy-relationships-admin-origin
ALTER TABLE copy_relationships ADD COLUMN originating_admin_action BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE copy_relationships DROP CONSTRAINT chk_exactly_one_origin;
ALTER TABLE copy_relationships ADD CONSTRAINT chk_exactly_one_origin CHECK (
    (originating_invitation_id IS NOT NULL)::int
    + (originating_follow_request_id IS NOT NULL)::int
    + originating_individual_setup::int
    + originating_admin_action::int = 1
);
--rollback ALTER TABLE copy_relationships DROP CONSTRAINT chk_exactly_one_origin;
--rollback ALTER TABLE copy_relationships ADD CONSTRAINT chk_exactly_one_origin CHECK ((originating_invitation_id IS NOT NULL)::int + (originating_follow_request_id IS NOT NULL)::int + originating_individual_setup::int = 1);
--rollback ALTER TABLE copy_relationships DROP COLUMN originating_admin_action;
