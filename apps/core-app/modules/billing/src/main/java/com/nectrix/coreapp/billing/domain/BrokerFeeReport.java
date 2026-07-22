package com.nectrix.coreapp.billing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code broker_fee_reports} table (007-billing.sql) — TICKET-120. {@code status}
 * progresses {@code DRAFT -> SENT -> BROKER_CONFIRMED_DEDUCTED -> BROKER_CONFIRMED_PAID} (or {@code
 * FAILED}, terminal, not automated here — a human marks a report failed if the broker never pays),
 * each transition cascading to every bundled {@code performance_fee_ledger} row's own status (see
 * {@code BrokerFeeReportService}).
 */
public record BrokerFeeReport(
    UUID id,
    UUID masterProfileId,
    String brokerType,
    Instant periodStart,
    Instant periodEnd,
    String status,
    String reportObjectKey,
    Instant sentAt,
    Instant confirmedDeductedAt,
    Instant confirmedPaidAt,
    UUID generatedByUserId,
    Instant createdAt) {}
