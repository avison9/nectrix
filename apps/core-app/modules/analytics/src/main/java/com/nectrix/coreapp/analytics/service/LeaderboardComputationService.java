package com.nectrix.coreapp.analytics.service;

import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.DailyEquityPoint;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.MasterProfileRef;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.WinRateInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * TICKET-112 — computes {@code leaderboard_snapshots} rows per master, per period
 * (docs/09-money-management-risk-formulas.md §9.8's formulas), from {@code account_snapshots}
 * (equity curve) and {@code copied_trades} (closed-trade P&L). Batch-computed, never live-queried
 * by the discovery API (docs/12-analytics-notifications-admin.md §12.1) — see {@code
 * LeaderboardScheduler} for the hourly trigger.
 *
 * <p><b>Two deliberate, documented gaps against §9.8's literal formulas</b> (both flagged here
 * rather than silently assumed):
 *
 * <ul>
 *   <li>{@code return_pct}'s formula subtracts {@code net_deposits} and adds {@code
 *       net_withdrawals} — no deposit/withdrawal ledger table exists anywhere in this schema, so
 *       both are always treated as zero. A mid-period deposit will be misread as profit until such
 *       a ledger exists.
 *   <li>{@code win_rate_pct} is computed from {@code copied_trades} (follower-side executions of
 *       this master's signals, aggregated across every follower currently or previously copying
 *       them) rather than "the master's own trades" literally — {@code trade_signals} (the master's
 *       own raw position events) has no {@code realized_pnl} column at all, so there is no data
 *       source for a master's own win rate directly. This also matches
 *       docs/10-portfolio-social-trading.md §10.2's own framing: verified metrics are meant to come
 *       "from actual copied-trade data on this platform," not the master's private account.
 * </ul>
 *
 * <p>{@code return_pct}/{@code max_drawdown_pct}/{@code sharpe_like_ratio} instead come from the
 * master's own {@code primary_broker_account_id}'s real {@code account_snapshots} equity curve —
 * the one part of §9.8 that maps cleanly to data this platform actually has.
 */
@Service
public class LeaderboardComputationService {

  /** docs/10-portfolio-social-trading.md §10.2 / this ticket's own period list. */
  private static final List<String> PERIODS = List.of("7D", "30D", "90D", "YTD", "ALL");

  private final LeaderboardComputationRepository repository;

  public LeaderboardComputationService(LeaderboardComputationRepository repository) {
    this.repository = repository;
  }

  /** Computes and inserts one new snapshot row per (master, period) that has any equity data. */
  public void computeAll() {
    Instant now = Instant.now();
    for (MasterProfileRef master : repository.findAllMasterProfiles()) {
      for (String period : PERIODS) {
        computeOne(master, period, periodStart(period, now));
      }
    }
  }

  private void computeOne(MasterProfileRef master, String period, Instant periodStart) {
    List<DailyEquityPoint> curve =
        repository.findDailyEquityCurve(master.primaryBrokerAccountId(), periodStart);
    // No equity data at all in this window — nothing meaningful to snapshot (not even a zero
    // row); this master/period simply doesn't appear in the leaderboard yet.
    if (curve.isEmpty()) {
      return;
    }

    BigDecimal returnPct = computeReturnPct(curve);
    BigDecimal maxDrawdownPct = computeMaxDrawdownPct(curve);
    BigDecimal sharpeLikeRatio = computeSharpeLikeRatio(curve);

    WinRateInput winRateInput = repository.winRateInput(master.id(), periodStart);
    BigDecimal winRatePct =
        winRateInput.total() == 0
            ? null
            : BigDecimal.valueOf(winRateInput.wins())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(winRateInput.total()), 2, RoundingMode.HALF_UP);

    int followerCount = repository.followerCount(master.id());
    BigDecimal aumProxy = repository.aumProxy(master.id());

    repository.insertSnapshot(
        master.id(),
        period,
        returnPct,
        maxDrawdownPct,
        winRatePct,
        sharpeLikeRatio,
        followerCount,
        aumProxy);
  }

  private BigDecimal computeReturnPct(List<DailyEquityPoint> curve) {
    BigDecimal starting = curve.get(0).equity();
    BigDecimal ending = curve.get(curve.size() - 1).equity();
    if (starting.signum() == 0) {
      return BigDecimal.ZERO;
    }
    // net_withdrawals/net_deposits are always 0 here — see this class's own Javadoc.
    return ending
        .subtract(starting)
        .multiply(BigDecimal.valueOf(100))
        .divide(starting, 4, RoundingMode.HALF_UP);
  }

  private BigDecimal computeMaxDrawdownPct(List<DailyEquityPoint> curve) {
    double runningMax = curve.get(0).equity().doubleValue();
    double maxDrawdown = 0;
    for (DailyEquityPoint point : curve) {
      double equity = point.equity().doubleValue();
      runningMax = Math.max(runningMax, equity);
      if (runningMax > 0) {
        double drawdown = (runningMax - equity) / runningMax * 100;
        maxDrawdown = Math.max(maxDrawdown, drawdown);
      }
    }
    return BigDecimal.valueOf(maxDrawdown).setScale(4, RoundingMode.HALF_UP);
  }

  /**
   * "Sharpe-like" per §9.8's own naming — no risk-free-rate benchmark, not a rigorous academic
   * Sharpe ratio. Double-based (not BigDecimal) since this needs sqrt/population-stddev, neither of
   * which BigDecimal supports natively — standard practice for this kind of statistic.
   */
  private BigDecimal computeSharpeLikeRatio(List<DailyEquityPoint> curve) {
    if (curve.size() < 3) {
      // Fewer than 2 daily returns — a stddev of one point is meaningless (always 0), and the
      // ratio would divide by zero.
      return null;
    }
    double[] dailyReturns = new double[curve.size() - 1];
    for (int i = 1; i < curve.size(); i++) {
      double prev = curve.get(i - 1).equity().doubleValue();
      double cur = curve.get(i).equity().doubleValue();
      dailyReturns[i - 1] = prev == 0 ? 0 : (cur - prev) / prev;
    }
    double mean = mean(dailyReturns);
    double stddev = populationStdDev(dailyReturns, mean);
    if (stddev == 0) {
      return null;
    }
    double sharpe = (mean / stddev) * Math.sqrt(252);
    return BigDecimal.valueOf(sharpe).setScale(4, RoundingMode.HALF_UP);
  }

  private double mean(double[] values) {
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  private double populationStdDev(double[] values, double mean) {
    double sumSquaredDiffs = 0;
    for (double v : values) {
      sumSquaredDiffs += (v - mean) * (v - mean);
    }
    return Math.sqrt(sumSquaredDiffs / values.length);
  }

  private static final Map<String, Long> FIXED_WINDOW_DAYS =
      Map.of("7D", 7L, "30D", 30L, "90D", 90L);

  private Instant periodStart(String period, Instant now) {
    Long fixedDays = FIXED_WINDOW_DAYS.get(period);
    if (fixedDays != null) {
      return now.minus(fixedDays, ChronoUnit.DAYS);
    }
    if ("YTD".equals(period)) {
      return now.atZone(ZoneOffset.UTC)
          .toLocalDate()
          .withDayOfYear(1)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant();
    }
    // ALL
    return Instant.EPOCH;
  }
}
