package com.nectrix.coreapp.trading.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plain-Java bundle of every {@code trading}-owned row tied to one broker account being archived —
 * deliberately not {@code trading.domain} types (see {@code CopyRelationshipArchivalApi}'s own
 * Javadoc for why).
 */
public record CopyRelationshipArchiveExport(
    List<CopyRelationshipRecord> copyRelationships,
    List<CopiedTradeRecord> copiedTrades,
    List<TradeSignalRecord> tradeSignals) {

  public record CopyRelationshipRecord(
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

  public record CopiedTradeRecord(
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

  public record TradeSignalRecord(
      UUID id,
      UUID masterBrokerAccountId,
      String brokerPositionId,
      String eventType,
      String canonicalSymbol,
      String direction,
      BigDecimal volumeLots,
      BigDecimal closedVolumeLots,
      BigDecimal fillPrice,
      BigDecimal slPrice,
      BigDecimal tpPrice,
      Instant serverTimestamp,
      Instant receivedAtGateway,
      String rawPayload) {}
}
