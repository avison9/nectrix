package com.nectrix.coreapp.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TICKET-117 — the cross-module surface {@code admin} module's {@code AdminController} calls into
 * for dispute raise/list/detail/resolve, rather than reaching {@code billing.repository} directly
 * (enforced by {@code ModuleBoundaryArchTest}). Deliberately returns only these module's own view
 * records, never {@code performance_fee_ledger}'s repository row types — same "own mirror type"
 * reasoning as {@code invitations.api.BrokerAccountView}'s Javadoc.
 */
public interface FeeLedgerAdminApi {

  List<FeeLedgerSummaryView> listByStatus(String status, int page, int pageSize);

  /**
   * @throws java.util.NoSuchElementException if no such ledger row exists.
   */
  FeeLedgerDetailView getDetail(UUID ledgerId);

  /**
   * The {@code copied_trades} underlying a ledger row's own period window, for the dispute detail
   * view.
   */
  List<UnderlyingTradeView> listUnderlyingTrades(UUID ledgerId);

  /** Marks a ledger row DISPUTED — the only real way {@code status=DISPUTED} can ever be set. */
  void dispute(UUID ledgerId);

  /**
   * Inserts one {@code fee_ledger_resolutions} row (the compensating-record pattern — see that
   * table's own migration Javadoc) and transitions the ledger row's status: {@code VOID} resolves
   * to {@code VOID}; {@code UPHOLD}/{@code ADJUST} both return the row to {@code INVOICED} (the
   * status a dispute is always raised from — see {@code AdminController#raiseDispute}'s own RBAC
   * comment) so its Stripe-invoicing/broker-reporting lifecycle can continue. The original {@code
   * computation_detail}/amounts on the ledger row itself are never touched, only its status —
   * {@code adjustedAmount} lives solely on the new resolution row.
   *
   * @param adjustedAmount nullable — only meaningful for {@code ADJUST}.
   */
  void resolve(
      UUID ledgerId,
      String resolution,
      String note,
      BigDecimal adjustedAmount,
      UUID resolvedByUserId);

  record FeeLedgerSummaryView(
      UUID id,
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String status) {}

  record FeeLedgerDetailView(
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

  record UnderlyingTradeView(
      UUID id,
      String canonicalSymbol,
      String direction,
      BigDecimal computedVolumeLots,
      String status,
      BigDecimal realizedPnl,
      Instant openedAt,
      Instant closedAt) {}
}
