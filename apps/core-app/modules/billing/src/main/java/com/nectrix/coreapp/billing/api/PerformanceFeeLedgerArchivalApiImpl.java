package com.nectrix.coreapp.billing.api;

import com.nectrix.coreapp.billing.api.PerformanceFeeLedgerArchiveExport.FeeLedgerRecord;
import com.nectrix.coreapp.billing.api.PerformanceFeeLedgerArchiveExport.ManagementAgreementRecord;
import com.nectrix.coreapp.billing.repository.ManagementAgreementArchivalRepository;
import com.nectrix.coreapp.billing.repository.ManagementAgreementArchivalRepository.ManagementAgreementExportRow;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerExportRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PerformanceFeeLedgerArchivalApiImpl implements PerformanceFeeLedgerArchivalApi {

  private final PerformanceFeeLedgerRepository performanceFeeLedgerRepository;
  private final ManagementAgreementArchivalRepository managementAgreementRepository;

  public PerformanceFeeLedgerArchivalApiImpl(
      PerformanceFeeLedgerRepository performanceFeeLedgerRepository,
      ManagementAgreementArchivalRepository managementAgreementRepository) {
    this.performanceFeeLedgerRepository = performanceFeeLedgerRepository;
    this.managementAgreementRepository = managementAgreementRepository;
  }

  @Override
  public PerformanceFeeLedgerArchiveExport findForExport(List<UUID> copyRelationshipIds) {
    List<LedgerExportRow> ledgerRows =
        performanceFeeLedgerRepository.findAllForRelationshipIds(copyRelationshipIds);
    List<ManagementAgreementExportRow> agreementRows =
        managementAgreementRepository.findAllForRelationshipIds(copyRelationshipIds);
    return new PerformanceFeeLedgerArchiveExport(
        ledgerRows.stream().map(this::toRecord).toList(),
        agreementRows.stream().map(this::toRecord).toList());
  }

  @Override
  public void deleteForRelationships(List<UUID> copyRelationshipIds) {
    performanceFeeLedgerRepository.deleteForRelationshipIds(copyRelationshipIds);
  }

  private FeeLedgerRecord toRecord(LedgerExportRow r) {
    return new FeeLedgerRecord(
        r.id(),
        r.copyRelationshipId(),
        r.periodStart(),
        r.periodEnd(),
        r.startingHwm(),
        r.endingEquity(),
        r.newProfitAboveHwm(),
        r.masterFeeAmount(),
        r.platformTakeAmount(),
        r.netToMasterAmount(),
        r.computationDetailJson(),
        r.status(),
        r.createdAt());
  }

  private ManagementAgreementRecord toRecord(ManagementAgreementExportRow r) {
    return new ManagementAgreementRecord(
        r.id(),
        r.copyRelationshipId(),
        r.agreementVersion(),
        r.status(),
        r.documentObjectKey(),
        r.signatureReference(),
        r.signedAt(),
        r.createdAt());
  }
}
