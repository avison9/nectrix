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
    BigDecimal computedVolumeLots,
    BigDecimal requestedPrice,
    BigDecimal filledPrice,
    BigDecimal slippagePips,
    String rejectReason,
    BigDecimal realizedPnl,
    Instant openedAt,
    Instant closedAt,
    Instant createdAt) {}
