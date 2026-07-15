package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code copy_relationships} table (006-copy-trading.sql) — TICKET-111. Holds foreign
 * keys as bare {@link UUID}s, not nested objects (1:1 with the DB row, same convention as {@code
 * BrokerAccount}) — {@code CopyRelationshipController} composes the richer nested
 * moneyManagementProfile/riskProfile response shape docs/14-api-specification.md §14.5 shows, by
 * additionally calling {@code MoneyManagementProfileRepository}/{@code RiskProfileRepository}.
 *
 * <p>{@code status} progresses {@code PENDING_RISK_ACK -> [PENDING_AGREEMENT if
 * feeCollectionMethod=BROKER_PARTNERSHIP] -> ACTIVE <-> PAUSED -> STOPPED} (terminal) — see {@link
 * com.nectrix.coreapp.trading.service.CopyRelationshipService} for the transition logic. Exactly
 * one of {@code originatingInvitationId}/{@code originatingFollowRequestId} is ever non-null (DB
 * {@code chk_exactly_one_origin} CHECK) — TICKET-111 never creates a row itself (that's
 * TICKET-118's invite-acceptance flow, or Phase 2's FollowRequest path), it only operates on rows
 * that already exist.
 */
public record CopyRelationship(
    UUID id,
    UUID masterProfileId,
    UUID masterBrokerAccountId,
    UUID followerUserId,
    UUID followerBrokerAccountId,
    UUID moneyManagementProfileId,
    UUID riskProfileId,
    String status,
    String copyDirection,
    BigDecimal performanceFeePercent,
    String feeCollectionMethod,
    BigDecimal highWaterMark,
    Instant riskAckAt,
    UUID originatingInvitationId,
    UUID originatingFollowRequestId,
    Instant createdAt,
    Instant stoppedAt) {}
