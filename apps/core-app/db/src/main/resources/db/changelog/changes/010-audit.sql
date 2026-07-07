--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Audit (cross-cutting)". Write-restricted
-- for the nectrix_app runtime role (013-app-role-grants.sql) per
-- docs/17-security-architecture.md §17.6 — no UPDATE/DELETE grants, so even a
-- compromised application credential can't rewrite history.
--changeset nectrix:010-audit-log
CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id),
    actor_type   TEXT NOT NULL CHECK (actor_type IN ('USER','ADMIN','SYSTEM')),
    action       TEXT NOT NULL,
    target_type  TEXT,
    target_id    TEXT,
    metadata     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
--rollback DROP TABLE IF EXISTS audit_log CASCADE;
