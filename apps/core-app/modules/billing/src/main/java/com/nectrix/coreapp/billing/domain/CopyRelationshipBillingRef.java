package com.nectrix.coreapp.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The subset of a {@code copy_relationships} row the settlement engine needs — read directly via
 * SQL from {@code trading}'s own table (see this module's build.gradle.kts comment for why that's
 * not a module-boundary violation), never through a Java cross-module dependency.
 */
public record CopyRelationshipBillingRef(
    UUID id,
    UUID followerUserId,
    UUID followerBrokerAccountId,
    BigDecimal highWaterMark,
    BigDecimal performanceFeePercent,
    String feeCollectionMethod,
    String status,
    Instant riskAckAt,
    Instant createdAt,
    Instant stoppedAt) {}
