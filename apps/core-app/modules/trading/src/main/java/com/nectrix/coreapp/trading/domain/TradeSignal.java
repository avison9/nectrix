package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code trade_signals} table (006-copy-trading.sql) — the Master's own raw broadcast
 * event a {@code copied_trades} row fans out from. {@code rawPayload} is the JSONB column as raw
 * text (same {@code ::text} cast convention {@code PerformanceFeeLedgerRepository}'s own {@code
 * computation_detail} column already establishes, avoiding a PGobject type handler). Read-only from
 * this module's own repository today — TICKET-101 follow-up's archival export is the first caller
 * that also needs to delete rows here.
 */
public record TradeSignal(
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
