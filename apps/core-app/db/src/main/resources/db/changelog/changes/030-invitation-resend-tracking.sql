--liquibase formatted sql

-- TICKET-118 follow-up — invitation sending isn't a one-shot affair: a Master needs to be able to
-- resend a PENDING invite whose email never arrived, or revive an EXPIRED one, without creating a
-- brand-new row (InvitationService#resend).
--changeset nectrix:030-invitation-resend-tracking
ALTER TABLE invitations
    ADD COLUMN resend_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_resent_at TIMESTAMPTZ;
--rollback ALTER TABLE invitations DROP COLUMN resend_count, DROP COLUMN last_resent_at;
