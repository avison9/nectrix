package com.nectrix.coreapp.billing.service;

import com.nectrix.coreapp.billing.config.BillingProperties;
import com.nectrix.coreapp.billing.domain.CopyRelationshipBillingRef;
import com.nectrix.coreapp.billing.repository.HighWaterMarkRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.repository.SettlementDataRepository;
import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BillingEvent;
import com.nectrix.events.v1.BillingEventType;
import com.nectrix.events.v1.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-113 — the settlement orchestration loop, docs/11-fee-engine-billing.md §11.4's sequence
 * diagram. A "period" here is a rolling 30-day window anchored to each relationship's own
 * risk-ack/creation time (or its last settlement), not calendar-month boundaries — this is this
 * codebase's own interpretation of "monthly" (documented, not assumed): it's trivially idempotent
 * (each window is exactly [cursor, cursor+30d)), and satisfies §11.4's own "must be consistent per
 * relationship for the life of the relationship" requirement more simply than calendar-month
 * arithmetic (whose month lengths vary) would.
 */
@Service
public class SettlementSchedulerService {

  private static final Logger log = LoggerFactory.getLogger(SettlementSchedulerService.class);
  private static final long PERIOD_DAYS = 30;

  private final SettlementDataRepository dataRepository;
  private final HighWaterMarkRepository hwmRepository;
  private final PerformanceFeeLedgerRepository ledgerRepository;
  private final SettlementCalculationService calculationService;
  private final StripeInvoicingService stripeInvoicingService;
  private final EventProducer<BillingEvent> billingEventProducer;
  private final ObjectMapper objectMapper;
  private final BillingProperties billingProperties;

  public SettlementSchedulerService(
      SettlementDataRepository dataRepository,
      HighWaterMarkRepository hwmRepository,
      PerformanceFeeLedgerRepository ledgerRepository,
      SettlementCalculationService calculationService,
      StripeInvoicingService stripeInvoicingService,
      EventProducer<BillingEvent> billingEventProducer,
      ObjectMapper objectMapper,
      BillingProperties billingProperties) {
    this.dataRepository = dataRepository;
    this.hwmRepository = hwmRepository;
    this.ledgerRepository = ledgerRepository;
    this.calculationService = calculationService;
    this.stripeInvoicingService = stripeInvoicingService;
    this.billingEventProducer = billingEventProducer;
    this.objectMapper = objectMapper;
    this.billingProperties = billingProperties;
  }

  public void runSettlement() {
    Instant now = Instant.now();
    List<CopyRelationshipBillingRef> relationships =
        dataRepository.findRelationshipsForSettlement();
    for (CopyRelationshipBillingRef rel : relationships) {
      try {
        if ("STOPPED".equals(rel.status())) {
          settleFinalIfNeeded(rel, now);
        } else {
          settleUnbilledPeriods(rel, now);
        }
      } catch (Exception e) {
        // One relationship's failure must never block every other relationship's settlement in
        // the same run — same "one-bad-account-never-blocks-the-others" precedent copy-engine's
        // own drawdown/reconciliation loops already established.
        log.error("billing: settlement failed for copyRelationshipId={}", rel.id(), e);
      }
    }
  }

  private void settleUnbilledPeriods(CopyRelationshipBillingRef rel, Instant now) {
    Instant cursor =
        ledgerRepository
            .findLastPeriodEnd(rel.id())
            .orElse(rel.riskAckAt() != null ? rel.riskAckAt() : rel.createdAt());
    while (true) {
      Instant periodEnd = cursor.plus(PERIOD_DAYS, ChronoUnit.DAYS);
      if (periodEnd.isAfter(now)) {
        break; // this period isn't complete yet
      }
      boolean settled = settleOnePeriod(rel, cursor, periodEnd);
      if (!settled) {
        // No account_snapshots data at all for this window (e.g. a brand-new linked account) --
        // stop for this relationship this run; retried from the same cursor next run once real
        // data exists, rather than silently skipping the period forever.
        break;
      }
      cursor = periodEnd;
    }
  }

  private void settleFinalIfNeeded(CopyRelationshipBillingRef rel, Instant now) {
    if (rel.stoppedAt() == null) {
      return;
    }
    if (ledgerRepository.hasFinalSettlementAt(rel.id(), rel.stoppedAt())) {
      return;
    }
    Instant periodStart =
        ledgerRepository
            .findLastPeriodEnd(rel.id())
            .orElse(rel.riskAckAt() != null ? rel.riskAckAt() : rel.createdAt());
    if (!periodStart.isBefore(rel.stoppedAt())) {
      return; // nothing new to bill
    }
    settleOnePeriod(rel, periodStart, rel.stoppedAt());
  }

  /**
   * @return false if there was no account data at all to settle against (caller should stop
   *     advancing).
   */
  private boolean settleOnePeriod(
      CopyRelationshipBillingRef rel, Instant periodStart, Instant periodEnd) {
    Optional<BigDecimal> startBalance =
        dataRepository.findBalanceAtOrBefore(rel.followerBrokerAccountId(), periodStart);
    Optional<BigDecimal> endBalance =
        dataRepository.findBalanceAtOrBefore(rel.followerBrokerAccountId(), periodEnd);
    Optional<BigDecimal> endingEquity =
        dataRepository.findEquityAtOrBefore(rel.followerBrokerAccountId(), periodEnd);
    if (startBalance.isEmpty() || endBalance.isEmpty() || endingEquity.isEmpty()) {
      return false;
    }

    BigDecimal realizedPnl = dataRepository.sumRealizedPnl(rel.id(), periodStart, periodEnd);
    SettlementComputation computation =
        calculationService.compute(
            rel.highWaterMark(),
            startBalance.get(),
            endBalance.get(),
            endingEquity.get(),
            realizedPnl,
            rel.performanceFeePercent(),
            billingProperties.platformTakeRatePct());

    Optional<UUID> ledgerId =
        ledgerRepository.tryInsert(
            rel.id(),
            periodStart,
            periodEnd,
            computation.startingHwm(),
            computation.endingEquity(),
            computation.newProfitAboveHwm(),
            computation.masterFeeAmount(),
            computation.platformTakeAmount(),
            computation.netToMasterAmount(),
            computation.toDetailJson(objectMapper));
    if (ledgerId.isEmpty()) {
      return true; // already settled -- a genuine idempotent no-op, still advance the cursor
    }

    applyHwmChange(rel, computation);
    publishFeePeriodClosed(rel.followerUserId(), ledgerId.get());

    if ("STRIPE_INVOICE".equals(rel.feeCollectionMethod())
        && computation.masterFeeAmount().signum() > 0) {
      stripeInvoicingService.invoice(rel, ledgerId.get(), computation.masterFeeAmount());
    }
    return true;
  }

  /**
   * Single actual column update to the final value (if it changed at all) — the two-step
   * profit-then-deposit/withdrawal distinction only matters for the history table's own
   * traceability, not for how many times the live column is written.
   */
  private void applyHwmChange(CopyRelationshipBillingRef rel, SettlementComputation computation) {
    if (!computation.newHwm().equals(rel.highWaterMark())) {
      hwmRepository.updateHwm(rel.id(), computation.newHwm());
    }
    if (!computation.baseNewHwm().equals(rel.highWaterMark())) {
      hwmRepository.insertHistory(rel.id(), computation.baseNewHwm(), "NEW_EQUITY_HIGH");
    }
    if (computation.netDeposits().signum() > 0) {
      hwmRepository.insertHistory(rel.id(), computation.newHwm(), "DEPOSIT_ADJUSTMENT");
    } else if (computation.netWithdrawals().signum() > 0) {
      hwmRepository.insertHistory(rel.id(), computation.newHwm(), "WITHDRAWAL_ADJUSTMENT");
    }
  }

  private void publishFeePeriodClosed(UUID followerUserId, UUID ledgerId) {
    EventEnvelope envelope =
        EventEnvelope.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setOccurredAt(Instant.now().toString())
            .setSchemaVersion("v1")
            .build();
    BillingEvent event =
        BillingEvent.newBuilder()
            .setEnvelope(envelope)
            .setUserId(followerUserId.toString())
            .setEventType(BillingEventType.BILLING_EVENT_TYPE_FEE_PERIOD_CLOSED)
            .setInvoiceId(ledgerId.toString())
            .build();
    billingEventProducer.send(followerUserId.toString(), event);
  }
}
