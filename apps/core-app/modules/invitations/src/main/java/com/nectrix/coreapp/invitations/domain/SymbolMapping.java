package com.nectrix.coreapp.invitations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code symbol_mappings} table (004-broker-connectivity.sql,
 * 019-symbol-mapping-confirmation.sql) — TICKET-103. {@code isConfirmed=false} rows are
 * auto-suggestions (nectrix_plan/docs/08-copy-trading-engine.md §8.4): populated by
 * apps/broker-adapters/apps/mt5-bridge-gateway via the internal suggestions endpoint, never used
 * for live copying (apps/copy-engine/internal/pipeline/dispatch.go only ever queries {@code WHERE
 * is_confirmed = TRUE}) until a user/admin confirms via {@code PUT
 * /api/v1/broker-accounts/{id}/symbol-mappings/{canonicalSymbol}}.
 */
public record SymbolMapping(
    long id,
    UUID brokerAccountId,
    String canonicalSymbol,
    String brokerSymbolName,
    double contractSize,
    double lotStep,
    double minLot,
    double maxLot,
    double pipSize,
    short digits,
    String marginCurrency,
    boolean isConfirmed,
    Instant confirmedAt,
    UUID confirmedByUserId) {}
