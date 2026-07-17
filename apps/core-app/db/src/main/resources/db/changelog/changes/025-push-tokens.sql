--liquibase formatted sql

-- TICKET-115: FCM push delivery fundamentally requires a per-device registration token -- neither
-- docs/06-database-schema.md nor any prior migration defines one (confirmed by search: no
-- device_token/push_token/fcm_token table exists anywhere). Minimal addition, not full device
-- management (no platform-specific metadata beyond what's needed to route a send) -- a real client
-- registration flow (mobile app or web-push service worker) is out of this ticket's own scope, same
-- as the raw-APNs reduction already agreed; this just gives NotificationDispatchService somewhere
-- real to read from, and something for tests to seed directly.
--changeset nectrix:025-push-tokens
CREATE TABLE push_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL,
    platform    TEXT NOT NULL CHECK (platform IN ('IOS','ANDROID','WEB')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, token)
);
CREATE INDEX idx_push_tokens_user ON push_tokens(user_id);
--rollback DROP TABLE IF EXISTS push_tokens CASCADE;
