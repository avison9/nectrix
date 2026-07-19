package com.nectrix.coreapp.billing.service;

import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerDetail;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerSummary;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.UnderlyingTrade;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-117 follow-up — real settlement/invoice visibility and self-service dispute-raising for
 * the two real parties to a settlement (a Master and a Follower), not just admin/support. Mirrors
 * {@code billing.api.FeeLedgerAdminApi}'s shape but every method is ownership-scoped to the caller
 * — this is the self-service surface, {@code FeeLedgerAdminApi} stays the staff one.
 */
@Service
public class FeeLedgerService {

  private final PerformanceFeeLedgerRepository ledgerRepository;

  public FeeLedgerService(PerformanceFeeLedgerRepository ledgerRepository) {
    this.ledgerRepository = ledgerRepository;
  }

  public List<LedgerSummary> listMine(UUID userId, int page, int pageSize) {
    return ledgerRepository.findAllForUser(userId, page, pageSize);
  }

  public record DetailWithTrades(LedgerDetail detail, List<UnderlyingTrade> trades) {}

  public DetailWithTrades getMine(UUID userId, UUID ledgerId) {
    LedgerDetail detail =
        ledgerRepository
            .findDetailForUser(ledgerId, userId)
            .orElseThrow(FeeLedgerNotFoundException::new);
    List<UnderlyingTrade> trades =
        ledgerRepository.findUnderlyingTrades(
            detail.copyRelationshipId(), detail.periodStart(), detail.periodEnd());
    return new DetailWithTrades(detail, trades);
  }

  /**
   * Either party (Master or Follower) can raise a dispute on their own settlement row. Rejects a
   * row that's already {@code DISPUTED} (no duplicate dispute) or {@code VOID} (nothing left to
   * dispute — a resolver already voided it).
   */
  public void raiseDispute(UUID userId, UUID ledgerId) {
    LedgerDetail detail =
        ledgerRepository
            .findDetailForUser(ledgerId, userId)
            .orElseThrow(FeeLedgerNotFoundException::new);
    if ("DISPUTED".equals(detail.status()) || "VOID".equals(detail.status())) {
      throw new FeeLedgerAlreadyDisputedException();
    }
    ledgerRepository.updateStatus(ledgerId, "DISPUTED");
  }
}
