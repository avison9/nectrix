--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Notifications".
--changeset nectrix:009-notification-preferences
CREATE TABLE notification_preferences (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type   TEXT NOT NULL,
    channel      TEXT NOT NULL CHECK (channel IN ('PUSH','EMAIL','SMS','IN_APP')),
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id, event_type, channel)
);
--rollback DROP TABLE IF EXISTS notification_preferences CASCADE;

--changeset nectrix:009-notification-log
CREATE TABLE notification_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    event_type   TEXT NOT NULL,
    channel      TEXT NOT NULL,
    payload      JSONB NOT NULL,
    sent_at      TIMESTAMPTZ,
    status       TEXT NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','SENT','FAILED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS notification_log CASCADE;
