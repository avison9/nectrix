package com.nectrix.coreapp.billing.api;

import com.nectrix.coreapp.billing.repository.FeeLedgerResolutionRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerDetail;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerSummary;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.UnderlyingTrade;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FeeLedgerAdminApiImpl implements FeeLedgerAdminApi {

  private final PerformanceFeeLedgerRepository ledgerRepository;
  private final FeeLedgerResolutionRepository resolutionRepository;

  public FeeLedgerAdminApiImpl(
      PerformanceFeeLedgerRepository ledgerRepository,
      FeeLedgerResolutionRepository resolutionRepository) {
    this.ledgerRepository = ledgerRepository;
    this.resolutionRepository = resolutionRepository;
  }

  @Override
  public List<FeeLedgerSummaryView> listByStatus(String status, int page, int pageSize) {
    return ledgerRepository.findByStatus(status, page, pageSize).stream()
        .map(this::toSummaryView)
        .toList();
  }

  @Override
  public FeeLedgerDetailView getDetail(UUID ledgerId) {
    LedgerDetail detail =
        ledgerRepository
            .findDetailById(ledgerId)
            .orElseThrow(() -> new NoSuchElementException("No such fee ledger row: " + ledgerId));
    return toDetailView(detail);
  }

  @Override
  public List<UnderlyingTradeView> listUnderlyingTrades(UUID ledgerId) {
    LedgerDetail detail =
        ledgerRepository
            .findDetailById(ledgerId)
            .orElseThrow(() -> new NoSuchElementException("No such fee ledger row: " + ledgerId));
    return ledgerRepository
        .findUnderlyingTrades(detail.copyRelationshipId(), detail.periodStart(), detail.periodEnd())
        .stream()
        .map(this::toTradeView)
        .toList();
  }

  @Override
  public void dispute(UUID ledgerId) {
    ledgerRepository.updateStatus(ledgerId, "DISPUTED");
  }

  /**
   * Requires the row to currently be DISPUTED — resolving an already-resolved row would insert a
   * second, spurious compensating record and silently re-flip the status. The frontend also guards
   * this (the resolve form disappears once a row's status leaves DISPUTED), but this is the real
   * enforcement, not just a UI nicety.
   */
  @Override
  public void resolve(
      UUID ledgerId,
      String resolution,
      String note,
      BigDecimal adjustedAmount,
      UUID resolvedByUserId) {
    PerformanceFeeLedgerRepository.LedgerRow row =
        ledgerRepository
            .findById(ledgerId)
            .orElseThrow(() -> new NoSuchElementException("No such fee ledger row: " + ledgerId));
    if (!"DISPUTED".equals(row.status())) {
      throw new IllegalStateException(
          "Fee ledger row " + ledgerId + " is not DISPUTED (status=" + row.status() + ")");
    }
    resolutionRepository.insert(ledgerId, resolution, note, adjustedAmount, resolvedByUserId);
    String newStatus = "VOID".equals(resolution) ? "VOID" : "INVOICED";
    ledgerRepository.updateStatus(ledgerId, newStatus);
  }

  private FeeLedgerSummaryView toSummaryView(LedgerSummary row) {
    return new FeeLedgerSummaryView(
        row.id(),
        row.copyRelationshipId(),
        row.periodStart(),
        row.periodEnd(),
        row.masterFeeAmount(),
        row.platformTakeAmount(),
        row.netToMasterAmount(),
        row.status());
  }

  private FeeLedgerDetailView toDetailView(LedgerDetail row) {
    return new FeeLedgerDetailView(
        row.id(),
        row.copyRelationshipId(),
        row.periodStart(),
        row.periodEnd(),
        row.startingHwm(),
        row.endingEquity(),
        row.newProfitAboveHwm(),
        row.masterFeeAmount(),
        row.platformTakeAmount(),
        row.netToMasterAmount(),
        row.computationDetailJson(),
        row.status());
  }

  private UnderlyingTradeView toTradeView(UnderlyingTrade row) {
    return new UnderlyingTradeView(
        row.id(),
        row.canonicalSymbol(),
        row.direction(),
        row.computedVolumeLots(),
        row.status(),
        row.realizedPnl(),
        row.openedAt(),
        row.closedAt());
  }
}
