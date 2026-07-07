--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Invitations & Onboarding". Forward
-- references (master_profiles, broker_ib_links, money_management_profiles,
-- risk_profiles, broker_accounts don't exist yet) are plain UUID columns
-- here — a genuine circular dependency in the domain, not an ordering
-- mistake (see the doc's own note at the top of this section). FKs added in
-- 011-deferred-foreign-keys.sql.
--changeset nectrix:003-invitations
CREATE TABLE invitations (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id        UUID NOT NULL,
    invited_email            CITEXT NOT NULL,
    token_hash               TEXT NOT NULL UNIQUE,
    status                   TEXT NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED')),
    suggested_broker_ib_link_id UUID,
    suggested_money_management_profile_id UUID,
    suggested_risk_profile_id UUID,
    created_by_user_id       UUID NOT NULL REFERENCES users(id),
    expires_at               TIMESTAMPTZ NOT NULL,
    accepted_at              TIMESTAMPTZ,
    accepted_by_user_id      UUID REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_invitations_master ON invitations(master_profile_id);
CREATE INDEX idx_invitations_status ON invitations(status) WHERE status = 'PENDING';
--rollback DROP TABLE IF EXISTS invitations CASCADE;

--changeset nectrix:003-follow-requests
CREATE TABLE follow_requests (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_user_id            UUID NOT NULL REFERENCES users(id),
    master_profile_id           UUID NOT NULL,
    follower_broker_account_id  UUID NOT NULL,
    proposed_money_management_profile_id UUID NOT NULL,
    proposed_risk_profile_id    UUID NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    decided_by_user_id          UUID REFERENCES users(id),
    decided_at                  TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_followrequests_master_pending ON follow_requests(master_profile_id) WHERE status = 'PENDING';
CREATE INDEX idx_followrequests_follower ON follow_requests(follower_user_id);
--rollback DROP TABLE IF EXISTS follow_requests CASCADE;
