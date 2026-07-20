package com.nectrix.coreapp.social.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The published shape of a {@code MasterProfile} for cross-module callers — deliberately a separate
 * type from {@code social.domain.MasterProfile}, not a re-export of it (same convention as {@code
 * trading.api.CopyRelationshipView}). Carries only the fields TICKET-118's invitation-acceptance
 * flow (and its own follow-up, {@code ProspectNominationService}, which needs {@code userId} to
 * notify the Master) need.
 */
public record MasterProfileSummaryView(
    UUID id,
    UUID userId,
    UUID primaryBrokerAccountId,
    String feeCollectionMethod,
    String displayName,
    BigDecimal performanceFeePercent) {}
