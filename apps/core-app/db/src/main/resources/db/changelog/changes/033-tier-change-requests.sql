--liquibase formatted sql

-- TICKET-122 — user-initiated Master/Follower tier-change requests, reviewed by an Admin/Super
-- Admin. Deliberately its own table, not a reuse of management_agreements (007-billing.sql): that
-- table's copy_relationship_id is NOT NULL UNIQUE, 1:1 with an existing copy_relationships row —
-- a tier-change request happens before any such row necessarily exists.
--changeset nectrix:033-tier-change-requests
CREATE TABLE tier_change_requests (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_role            TEXT NOT NULL CHECK (target_role IN ('MASTER','FOLLOWER')),
    status                 TEXT NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    agreement_version      TEXT NOT NULL,
    agreement_accepted_at  TIMESTAMPTZ NOT NULL,
    reviewed_by_user_id    UUID REFERENCES users(id),
    review_reason          TEXT,
    reviewed_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tierchangerequests_user ON tier_change_requests(user_id);
CREATE INDEX idx_tierchangerequests_status ON tier_change_requests(status);

-- The ticket's own "already has a pending request" rejection rule — a partial unique index rather
-- than a CHECK, since Postgres CHECK constraints can't reference other rows.
CREATE UNIQUE INDEX idx_tierchangerequests_one_pending_per_user
    ON tier_change_requests(user_id) WHERE status = 'PENDING';
--rollback DROP TABLE IF EXISTS tier_change_requests CASCADE;
