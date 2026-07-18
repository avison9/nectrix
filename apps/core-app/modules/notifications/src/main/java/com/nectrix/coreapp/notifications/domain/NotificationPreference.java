package com.nectrix.coreapp.notifications.domain;

import java.util.UUID;

/** Mirrors the {@code notification_preferences} table (009-notifications.sql). */
public record NotificationPreference(
    UUID userId, String eventType, String channel, boolean enabled) {}
