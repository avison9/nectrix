package com.nectrix.coreapp.social.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors the {@code master_profiles} table (005-social-marketplace.sql) — TICKET-111. A Master's
 * *account* is Admin-provisioned (the {@code MASTER} role is granted, not self-registered), but the
 * Master fills in this profile themselves, once, on first Admin Portal login
 * (nectrix_plan/docs/07-auth-onboarding-broker-linking.md §7.0) — {@code userId} is {@code UNIQUE},
 * so a second {@code POST /master-profiles} for the same user is a 409, not a second row.
 */
public record MasterProfile(
    UUID id,
    UUID userId,
    UUID primaryBrokerAccountId,
    String displayName,
    String bio,
    List<String> strategyTags,
    BigDecimal performanceFeePercent,
    String feeCollectionMethod,
    boolean isPublic,
    Instant verifiedAt,
    Instant createdAt,
    // Feature — the minimum broker-account balance a Follower must have to start copying this
    // Master. Null means no minimum (default, unchanged behavior). Enforced at copy-relationship
    // activation time, not an ongoing runtime check — see InvitationCopySetupService/
    // AdminCopyLinkService in modules:trading.
    BigDecimal minFollowerBalance) {}
