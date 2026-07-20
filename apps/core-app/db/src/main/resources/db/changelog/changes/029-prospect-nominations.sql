--liquibase formatted sql

-- TICKET-118 follow-up: the "Follower refers a prospect -> lands in their Master's inbox -> Master
-- sends a real invitation" flow (apps/web's /follower/referrals + /inbox pages both existed as
-- inert placeholders miscited to Phase 2's TICKET-207 referral-rewards program, which never
-- actually specified this mechanism). Deliberately a NEW table, not a reuse of `follow_requests` --
-- that table is Phase 2's different "existing Follower requests to additionally follow a second
-- Master" concept (docs/06-database-schema.md), with NOT NULL broker-account/MM/risk-profile FKs
-- that don't fit an unauthenticated-prospect nomination at all.
--changeset nectrix:029-prospect-nominations
CREATE TABLE prospect_nominations (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id    UUID NOT NULL REFERENCES master_profiles(id),
    nominated_by_user_id UUID NOT NULL REFERENCES users(id),
    prospect_email       CITEXT NOT NULL,
    status               TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'INVITED', 'DISMISSED')),
    invitation_id        UUID REFERENCES invitations(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at           TIMESTAMPTZ
);
CREATE INDEX idx_prospect_nominations_master_pending ON prospect_nominations(master_profile_id)
    WHERE status = 'PENDING';
--rollback DROP TABLE IF EXISTS prospect_nominations CASCADE;
