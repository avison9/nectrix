--liquibase formatted sql

-- TICKET-005 (Auth & Identity Service) — supports refresh-token rotation with
-- reuse-detection (docs/17-security-architecture.md §17.3). revoked_reason
-- distinguishes an ordinary single-device logout/rotation from a genuine
-- reuse-detected mass revocation, for observability. The unique index isn't
-- just performance (every /refresh call would otherwise full-scan `sessions`
-- as it grows) — a SHA-256 collision between two legitimately-issued tokens
-- isn't a real-world concern, so uniqueness doubles as a defensive integrity
-- constraint.
--changeset nectrix:015-sessions-revoked-reason
ALTER TABLE sessions ADD COLUMN revoked_reason TEXT
    CHECK (revoked_reason IN ('LOGOUT','ROTATED','REUSE_DETECTED','EXPIRED','ADMIN_REVOKED'));
--rollback ALTER TABLE sessions DROP COLUMN IF EXISTS revoked_reason;

--changeset nectrix:015-sessions-refresh-token-hash-index
CREATE UNIQUE INDEX idx_sessions_refresh_token_hash ON sessions(refresh_token_hash);
--rollback DROP INDEX IF EXISTS idx_sessions_refresh_token_hash;
