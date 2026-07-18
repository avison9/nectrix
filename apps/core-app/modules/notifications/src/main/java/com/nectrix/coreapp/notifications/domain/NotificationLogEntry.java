package com.nectrix.coreapp.notifications.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code notification_log} table (009-notifications.sql + 024's {@code read_at}
 * column). {@code channel='IN_APP'} rows double as the user-facing inbox feed — see
 * 024-notification-log-read-state.sql's own comment for why no separate table was added.
 */
public record NotificationLogEntry(
    UUID id,
    UUID userId,
    String eventType,
    String channel,
    String payload,
    Instant sentAt,
    String status,
    Instant createdAt,
    Instant readAt) {}
