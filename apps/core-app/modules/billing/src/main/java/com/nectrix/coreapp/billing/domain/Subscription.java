package com.nectrix.coreapp.billing.domain;

import java.time.Instant;
import java.util.UUID;

/** Mirrors the {@code subscriptions} table (007-billing.sql) — TICKET-114. */
public record Subscription(
    UUID id,
    UUID userId,
    String planCode,
    String status,
    Instant currentPeriodStart,
    Instant currentPeriodEnd,
    String stripeSubscriptionId,
    Instant createdAt) {}
