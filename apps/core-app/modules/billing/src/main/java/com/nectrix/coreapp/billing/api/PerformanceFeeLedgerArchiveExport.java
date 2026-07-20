package com.nectrix.coreapp.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plain-Java bundle of every {@code billing}-owned row tied to a set of {@code copy_relationships}
 * being archived — deliberately not {@code billing.repository} types (see {@code
 * PerformanceFeeLedgerArchivalApi}'s own Javadoc for why). Same shape as {@code
 * trading.api.CopyRelationshipArchiveExport}.
 */
public record PerformanceFeeLedgerArchiveExport(
    List<FeeLedgerRecord> feeLedgerRows, List<ManagementAgreementRecord> managementAgreements) {

  public record FeeLedgerRecord(
      UUID id,
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal startingHwm,
      BigDecimal endingEquity,
      BigDecimal newProfitAboveHwm,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String computationDetailJson,
      String status,
      Instant createdAt) {}

  public record ManagementAgreementRecord(
      UUID id,
      UUID copyRelationshipId,
      String agreementVersion,
      String status,
      String documentObjectKey,
      String signatureReference,
      Instant signedAt,
      Instant createdAt) {}
}
