package com.nectrix.coreapp.billing.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Every field docs/11-fee-engine-billing.md §11.2 requires captured verbatim into {@code
 * performance_fee_ledger.computation_detail} — this record's {@link #toDetailJson} is exactly that
 * JSON, so a dispute (§11.8) can reconstruct the calculation from this one record alone, without
 * re-querying mutable state.
 */
public record SettlementComputation(
    BigDecimal startingHwm,
    BigDecimal endingEquity,
    BigDecimal netDeposits,
    BigDecimal netWithdrawals,
    BigDecimal adjustedEndingEquity,
    BigDecimal newProfitAboveHwm,
    BigDecimal performanceFeePercent,
    BigDecimal platformTakeRatePct,
    BigDecimal masterFeeAmount,
    BigDecimal platformTakeAmount,
    BigDecimal netToMasterAmount,
    // The profit-driven step (§11.3's "new_hwm = adjusted_ending_equity if profit > 0 else
    // starting_hwm") BEFORE the separate deposit/withdrawal adjustment below is applied on top —
    // kept distinct from newHwm so HighWaterMarkRepository can log two separately-reasoned
    // history rows when both a profit-driven rise AND a deposit/withdrawal happened in the same
    // period, instead of collapsing two real causes into one opaque number.
    BigDecimal baseNewHwm,
    BigDecimal newHwm) {

  public String toDetailJson(ObjectMapper objectMapper) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("starting_hwm", startingHwm);
    detail.put("ending_equity", endingEquity);
    detail.put("net_deposits", netDeposits);
    detail.put("net_withdrawals", netWithdrawals);
    detail.put("adjusted_ending_equity", adjustedEndingEquity);
    detail.put("new_profit_above_hwm", newProfitAboveHwm);
    detail.put("performance_fee_percent", performanceFeePercent);
    detail.put("platform_take_rate_pct", platformTakeRatePct);
    detail.put("master_fee_amount", masterFeeAmount);
    detail.put("platform_take_amount", platformTakeAmount);
    detail.put("net_to_master_amount", netToMasterAmount);
    detail.put("new_hwm", newHwm);
    return objectMapper.writeValueAsString(detail);
  }
}
