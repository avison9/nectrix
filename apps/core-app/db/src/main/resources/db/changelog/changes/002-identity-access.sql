--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Identity & Access". created_via_invitation_id
-- is a forward reference (invitations doesn't exist yet) — plain UUID here,
-- FK added in 011-deferred-foreign-keys.sql, per the doc's own note.
--changeset nectrix:002-users
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT UNIQUE NOT NULL,
    password_hash       TEXT,
    display_name        TEXT NOT NULL,
    two_factor_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    two_factor_secret   TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    created_by_user_id  UUID REFERENCES users(id),
    created_via_invitation_id UUID,
    referred_by_user_id UUID REFERENCES users(id),
    region              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS users CASCADE;

--changeset nectrix:002-roles
CREATE TABLE roles (
    id    SMALLSERIAL PRIMARY KEY,
    name  TEXT UNIQUE NOT NULL
);
--rollback DROP TABLE IF EXISTS roles CASCADE;

--changeset nectrix:002-user-roles
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);
--rollback DROP TABLE IF EXISTS user_roles CASCADE;

--changeset nectrix:002-sessions
CREATE TABLE sessions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash TEXT NOT NULL,
    device_info   JSONB,
    ip_address    INET,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
--rollback DROP TABLE IF EXISTS sessions CASCADE;
