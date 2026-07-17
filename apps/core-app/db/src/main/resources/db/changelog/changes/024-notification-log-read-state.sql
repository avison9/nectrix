--liquibase formatted sql

-- TICKET-115: GET /api/v1/notifications?unread=true / POST /api/v1/notifications/{id}/read need a
-- per-user, per-event, single-row "inbox" concept with read/unread state -- notification_log
-- (009-notifications.sql) is a delivery-*attempt* audit trail (one row per channel per event, so a
-- single logical notification going out on 3 channels produces 3 rows), not that. Rather than add a
-- whole new user_notifications table, the inbox reuses notification_log's channel='IN_APP' rows
-- specifically as the feed -- exactly one IN_APP row per logical notification, no duplicate-row
-- problem, minimal schema footprint.
--changeset nectrix:024-notification-log-read-at
ALTER TABLE notification_log ADD COLUMN read_at TIMESTAMPTZ;
--rollback ALTER TABLE notification_log DROP COLUMN read_at;
