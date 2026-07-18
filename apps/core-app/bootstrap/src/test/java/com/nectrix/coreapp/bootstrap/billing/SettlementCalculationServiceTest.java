package com.nectrix.coreapp.bootstrap.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.billing.service.SettlementCalculationService;
import com.nectrix.coreapp.billing.service.SettlementComputation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * TICKET-113 — pure-function tests for docs/11-fee-engine-billing.md §11.1–§11.3's formulas, no
 * DB/Spring context needed (SettlementIntegrationTest covers the real end-to-end wiring). Every
 * scenario here is a hand-calculated figure, verified exactly.
 */
class SettlementCalculationServiceTest {

  private final SettlementCalculationService service = new SettlementCalculationService();

  private static final BigDecimal ZERO4 = new BigDecimal("0.0000");

  @Test
  void realProfitAboveHwm_chargesFeeAndRaisesHwmToAdjustedEquity() {
    // HWM=10000, no deposits/withdrawals (deltaBalance == realizedPnl), ending equity=12000.
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("12000"),
            new BigDecimal("12000"),
            new BigDecimal("2000"),
            new BigDecimal("20"), // performanceFeePercent
            new BigDecimal("15")); // platformTakeRatePct

    assertThat(result.netDeposits()).isEqualByComparingTo(ZERO4);
    assertThat(result.netWithdrawals()).isEqualByComparingTo(ZERO4);
    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo("2000.0000");
    assertThat(result.masterFeeAmount()).isEqualByComparingTo("400.0000"); // 2000 * 20%
    assertThat(result.platformTakeAmount()).isEqualByComparingTo("60.0000"); // 400 * 15%
    assertThat(result.netToMasterAmount()).isEqualByComparingTo("340.0000");
    assertThat(result.newHwm()).isEqualByComparingTo("12000.0000");
  }

  @Test
  void losingPeriod_noClawback_hwmUnchangedAndNoFee() {
    // HWM=10000, ending equity=9000 (a loss), no deposits/withdrawals.
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("9000"),
            new BigDecimal("9000"),
            new BigDecimal("-1000"),
            new BigDecimal("20"),
            new BigDecimal("15"));

    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo(ZERO4);
    assertThat(result.masterFeeAmount()).isEqualByComparingTo(ZERO4);
    // No clawback -- HWM stays at the prior peak, doesn't drop to follow equity down.
    assertThat(result.newHwm()).isEqualByComparingTo("10000.0000");
  }

  @Test
  void midPeriodDeposit_excludedFromProfit_butRaisesHwm() {
    // HWM=10000, follower deposits 5000, no real trading (realizedPnl=0), ending equity=15000.
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("15000"), // balance moved by exactly the deposit
            new BigDecimal("15000"),
            BigDecimal.ZERO, // no realized P&L this period
            new BigDecimal("20"),
            new BigDecimal("15"));

    assertThat(result.netDeposits()).isEqualByComparingTo("5000.0000");
    assertThat(result.netWithdrawals()).isEqualByComparingTo(ZERO4);
    // The deposit must not look like profit.
    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo(ZERO4);
    assertThat(result.masterFeeAmount()).isEqualByComparingTo(ZERO4);
    // But it does raise the bar for future profit.
    assertThat(result.newHwm()).isEqualByComparingTo("15000.0000");
  }

  @Test
  void midPeriodWithdrawal_excludedFromLoss_andLowersHwm() {
    // HWM=10000, follower withdraws 4000, no real trading, ending equity=6000.
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("6000"),
            new BigDecimal("6000"),
            BigDecimal.ZERO,
            new BigDecimal("20"),
            new BigDecimal("15"));

    assertThat(result.netWithdrawals()).isEqualByComparingTo("4000.0000");
    // A withdrawal must not look like a loss (fee-wise) -- adjusted equity is back at 10000.
    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo(ZERO4);
    assertThat(result.masterFeeAmount()).isEqualByComparingTo(ZERO4);
    // But the baseline lowers so the follower isn't later charged fees on capital already removed.
    assertThat(result.newHwm()).isEqualByComparingTo("6000.0000");
  }

  @Test
  void profitAndDepositInSamePeriod_bothApply() {
    // HWM=10000. Follower deposits 3000 AND makes 1000 real profit. Ending equity =
    // 10000+3000+1000=14000.
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("14000"), // deltaBalance = 4000
            new BigDecimal("14000"),
            new BigDecimal("1000"), // realizedPnl -- so netDepositOrWithdrawal = 4000 - 1000 = 3000
            new BigDecimal("20"),
            new BigDecimal("15"));

    assertThat(result.netDeposits()).isEqualByComparingTo("3000.0000");
    // adjustedEndingEquity = 14000 - 3000 = 11000 -> profit above 10000 HWM = 1000.
    assertThat(result.adjustedEndingEquity()).isEqualByComparingTo("11000.0000");
    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo("1000.0000");
    assertThat(result.masterFeeAmount()).isEqualByComparingTo("200.0000"); // 1000 * 20%
    // baseNewHwm (profit-driven) = adjustedEndingEquity = 11000, then +3000 deposit = 14000.
    assertThat(result.baseNewHwm()).isEqualByComparingTo("11000.0000");
    assertThat(result.newHwm()).isEqualByComparingTo("14000.0000");
  }

  @Test
  void zeroPerformanceFeePercent_neverChargesEvenWithProfit() {
    SettlementComputation result =
        service.compute(
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("12000"),
            new BigDecimal("12000"),
            new BigDecimal("2000"),
            BigDecimal.ZERO,
            new BigDecimal("15"));

    assertThat(result.newProfitAboveHwm()).isEqualByComparingTo("2000.0000");
    assertThat(result.masterFeeAmount()).isEqualByComparingTo(ZERO4);
    assertThat(result.platformTakeAmount()).isEqualByComparingTo(ZERO4);
    assertThat(result.netToMasterAmount()).isEqualByComparingTo(ZERO4);
  }
}
