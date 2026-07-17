package com.nectrix.coreapp.billing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Pure calculation — docs/11-fee-engine-billing.md §11.1–§11.3, exactly. No DB access of its own;
 * {@code SettlementSchedulerService} gathers the inputs and persists the outputs.
 *
 * <p><b>Deposit/withdrawal detection</b> (the "actual detection mechanism used," per this ticket's
 * own requirement to document it — see also this module's package-level notes): no
 * deposit/withdrawal event or ledger exists anywhere in this platform yet. cTrader's own protocol
 * does carry deposit-related execution types, but apps/broker-adapters discards them today
 * (stream.go: "an execution type this platform doesn't act on (rejection, swap, deposit, ...)") —
 * wiring that through end-to-end is real, separate, Go-side scope this ticket doesn't take on.
 * Instead, this uses the docs' own explicitly-sanctioned fallback: {@code balance} (not {@code
 * equity} — balance only moves on realized P&L or a genuine deposit/withdrawal/correction, never on
 * floating/unrealized P&L) delta over the period, minus realized P&L over the same period, is the
 * net deposit/withdrawal. Known, flagged limitation: this can't distinguish a genuine deposit from
 * a broker bonus/correction/swap-that-hits-balance.
 */
@Service
public class SettlementCalculationService {

  private static final int SCALE = 4;

  public SettlementComputation compute(
      BigDecimal startingHwm,
      BigDecimal startBalance,
      BigDecimal endBalance,
      BigDecimal endingEquity,
      BigDecimal realizedPnlOverPeriod,
      BigDecimal performanceFeePercent,
      BigDecimal platformTakeRatePct) {
    BigDecimal deltaBalance = endBalance.subtract(startBalance);
    BigDecimal netDepositOrWithdrawal = deltaBalance.subtract(realizedPnlOverPeriod);
    BigDecimal netDeposits = netDepositOrWithdrawal.max(BigDecimal.ZERO);
    BigDecimal netWithdrawals = netDepositOrWithdrawal.negate().max(BigDecimal.ZERO);

    // A deposit must not look like profit; a withdrawal must not look like a loss (§11.1).
    BigDecimal adjustedEndingEquity =
        endingEquity
            .subtract(netDeposits)
            .add(netWithdrawals)
            .setScale(SCALE, RoundingMode.HALF_UP);

    BigDecimal newProfitAboveHwm =
        adjustedEndingEquity
            .subtract(startingHwm)
            .max(BigDecimal.ZERO)
            .setScale(SCALE, RoundingMode.HALF_UP);

    BigDecimal masterFeeAmount =
        newProfitAboveHwm
            .multiply(performanceFeePercent)
            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
    BigDecimal platformTakeAmount =
        masterFeeAmount
            .multiply(platformTakeRatePct)
            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
    BigDecimal netToMasterAmount = masterFeeAmount.subtract(platformTakeAmount);

    // §11.3: profit-driven step first (no clawback on a losing period)...
    BigDecimal baseNewHwm = newProfitAboveHwm.signum() > 0 ? adjustedEndingEquity : startingHwm;
    // ...then the deposit/withdrawal adjustment is applied on top, separately and always.
    BigDecimal newHwm =
        baseNewHwm.add(netDeposits).subtract(netWithdrawals).setScale(SCALE, RoundingMode.HALF_UP);

    return new SettlementComputation(
        startingHwm,
        endingEquity,
        netDeposits,
        netWithdrawals,
        adjustedEndingEquity,
        newProfitAboveHwm,
        performanceFeePercent,
        platformTakeRatePct,
        masterFeeAmount,
        platformTakeAmount,
        netToMasterAmount,
        baseNewHwm,
        newHwm);
  }
}
