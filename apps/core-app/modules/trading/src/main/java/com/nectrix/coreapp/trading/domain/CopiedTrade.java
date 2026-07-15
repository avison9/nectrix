package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors `copied_trades` — read-only from this module's own API surface (TICKET-111's
 * trades-history view).
 */
public record CopiedTrade(
    UUID id,
    UUID copyRelationshipId,
    UUID tradeSignalId,
    String status,
    BigDecimal computedVolumeLots,
    BigDecimal requestedPrice,
    BigDecimal filledPrice,
    BigDecimal slippagePips,
    String rejectReason,
    BigDecimal realizedPnl,
    Instant openedAt,
    Instant closedAt,
    Instant createdAt) {}
