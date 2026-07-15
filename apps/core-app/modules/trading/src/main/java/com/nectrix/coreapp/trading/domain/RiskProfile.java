package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code risk_profiles} table (006-copy-trading.sql) — TICKET-105. {@code
 * drawdownPausePct}/{@code drawdownCloseAllPct} are read here for full-row fidelity but are
 * TICKET-108's own account-level drawdown-protection columns — {@link RiskProfileRepository}'s
 * insert/update never write them (always NULL from this ticket); TICKET-108 must extend the
 * repository the day it needs to.
 */
public record RiskProfile(
    UUID id,
    BigDecimal maxLotPerTrade,
    Integer maxOpenPositions,
    BigDecimal maxExposurePerSymbolLots,
    BigDecimal maxTotalExposureLots,
    BigDecimal maxSlippagePips,
    BigDecimal drawdownPausePct,
    BigDecimal drawdownCloseAllPct,
    Instant createdAt) {}
