package com.nectrix.coreapp.billing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Mirrors the {@code broker_fee_report_lines} table (007-billing.sql) — TICKET-120. */
public record BrokerFeeReportLine(
    long id,
    UUID brokerFeeReportId,
    UUID performanceFeeLedgerId,
    String followerBrokerAccountLogin,
    BigDecimal feeAmount,
    String currency) {}
