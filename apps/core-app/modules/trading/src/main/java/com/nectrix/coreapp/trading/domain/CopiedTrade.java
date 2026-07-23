package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors `copied_trades` — read-only from this module's own API surface (TICKET-111's
 * trades-history view). {@code canonicalSymbol} isn't a `copied_trades` column itself — it's pulled
 * in via a join to `trade_signals` (TICKET-116, needed for trade-history filtering/display; see
 * {@code CopiedTradeRepository}'s own queries).
 */
public record CopiedTrade(
    UUID id,
    UUID copyRelationshipId,
    UUID tradeSignalId,
    String status,
    String canonicalSymbol,
    // Bugfix — the Trade History page's TYPE column (BUY/SELL), pulled in via the same
    // trade_signals join canonicalSymbol already uses.
    String direction,
    BigDecimal computedVolumeLots,
    // TICKET-124 — the live remaining volume of an OPEN/PARTIALLY_CLOSED position (as opposed to
    // computedVolumeLots, the immutable original size) and the follower's own real broker
    // position id, both needed by UnrealizedPnlEnrichmentService to match this row against a real
    // open position from BrokerAdaptersInternalClient.getOpenPositions.
    BigDecimal currentOpenVolumeLots,
    String followerBrokerPositionId,
    BigDecimal requestedPrice,
    BigDecimal filledPrice,
    BigDecimal slippagePips,
    String rejectReason,
    BigDecimal realizedPnl,
    Instant openedAt,
    Instant closedAt,
    Instant createdAt) {}
