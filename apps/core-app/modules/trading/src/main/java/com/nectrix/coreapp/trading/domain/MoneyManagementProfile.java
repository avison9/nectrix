package com.nectrix.coreapp.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code money_management_profiles} table (006-copy-trading.sql) — TICKET-104. {@code
 * method} is one of the six lot-sizing methods
 * (nectrix_plan/docs/09-money-management-risk-formulas.md §9.2): {@code FIXED_LOT}, {@code
 * PROPORTIONAL_EQUITY}, {@code PROPORTIONAL_BALANCE}, {@code RISK_PERCENT}, {@code MULTIPLIER},
 * {@code CUSTOM_FORMULA}. Only the field(s) relevant to the chosen method are ever non-null (e.g.
 * {@code fixedLotSize} for {@code FIXED_LOT}) — enforced at the actual sizing computation
 * (apps/copy-engine/internal/moneymgmt), not by a DB constraint.
 *
 * <p>Uses {@link BigDecimal} for the {@code NUMERIC} columns per docs/09 §9.1's "all monetary math
 * must use fixed-point/decimal arithmetic ... never IEEE-754 floats" — a deliberate departure from
 * {@code SymbolMapping}'s {@code double} fields (TICKET-103), which the money-management formulas'
 * own decimal-arithmetic requirement does not apply to.
 */
public record MoneyManagementProfile(
    UUID id,
    String method,
    BigDecimal fixedLotSize,
    BigDecimal multiplier,
    BigDecimal riskPercent,
    String customFormulaExpr,
    String roundingMode,
    Instant createdAt) {}
