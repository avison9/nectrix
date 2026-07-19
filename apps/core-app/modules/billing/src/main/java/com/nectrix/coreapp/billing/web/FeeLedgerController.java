package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerDetail;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.LedgerSummary;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository.UnderlyingTrade;
import com.nectrix.coreapp.billing.service.FeeLedgerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-117 follow-up — a real Master or Follower's own settlement/invoice history, plus
 * self-service dispute-raising. Distinct from {@code admin.web.AdminController}'s {@code
 * /api/v1/admin/fee-ledger/*} routes (staff-only, sees every row); every route here is
 * ownership-scoped to the caller via {@link FeeLedgerService}.
 */
@RestController
public class FeeLedgerController {

  private final FeeLedgerService service;

  public FeeLedgerController(FeeLedgerService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/fee-ledger")
  public List<FeeLedgerSummaryView> listMine(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    return service.listMine(currentUserId(jwt), page, pageSize).stream()
        .map(FeeLedgerController::toSummaryView)
        .toList();
  }

  @GetMapping("/api/v1/fee-ledger/{id}")
  public FeeLedgerDetailResponse getMine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    var result = service.getMine(currentUserId(jwt), id);
    return new FeeLedgerDetailResponse(
        toDetailView(result.detail()),
        result.trades().stream().map(FeeLedgerController::toTradeView).toList());
  }

  @PostMapping("/api/v1/fee-ledger/{id}/dispute")
  public ResponseEntity<Void> raiseDispute(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.raiseDispute(currentUserId(jwt), id);
    return ResponseEntity.noContent().build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private static FeeLedgerSummaryView toSummaryView(LedgerSummary row) {
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

  private static FeeLedgerDetailView toDetailView(LedgerDetail row) {
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

  private static UnderlyingTradeView toTradeView(UnderlyingTrade row) {
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

  public record FeeLedgerSummaryView(
      UUID id,
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String status) {}

  public record FeeLedgerDetailView(
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
      String status) {}

  public record UnderlyingTradeView(
      UUID id,
      String canonicalSymbol,
      String direction,
      BigDecimal computedVolumeLots,
      String status,
      BigDecimal realizedPnl,
      Instant openedAt,
      Instant closedAt) {}

  public record FeeLedgerDetailResponse(
      FeeLedgerDetailView ledger, List<UnderlyingTradeView> trades) {}
}
