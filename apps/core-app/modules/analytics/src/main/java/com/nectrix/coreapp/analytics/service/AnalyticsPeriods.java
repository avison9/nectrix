package com.nectrix.coreapp.analytics.service;

import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.DailyEquityPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature — the period-window/monthly-returns math {@link MasterAnalyticsService} originally had as
 * private methods, extracted here so {@code FollowerAnalyticsService} can reuse the exact same
 * window semantics and month-bucketing rather than a second, potentially-drifting copy.
 */
public final class AnalyticsPeriods {

  private AnalyticsPeriods() {}

  /** Same period vocabulary as {@code LeaderboardComputationService}'s own {@code PERIODS}. */
  private static final Map<String, Long> FIXED_WINDOW_DAYS =
      Map.of("7D", 7L, "30D", 30L, "90D", 90L);

  /** Trailing window wide enough to always cover a full 12 calendar months of monthly returns. */
  public static final long MONTHLY_RETURNS_LOOKBACK_DAYS = 395L;

  public static Instant periodStart(String period, Instant now) {
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

  /**
   * Buckets the curve by calendar month (last known equity point of each month), then returns
   * month-over-month % change. The earliest month in the lookback window has no prior month to
   * compare against, so it's excluded from the output entirely — same "no meaningful zero row"
   * reasoning {@code LeaderboardComputationService} already applies to an empty curve.
   */
  public static List<MonthlyReturn> computeMonthlyReturns(List<DailyEquityPoint> curve) {
    Map<YearMonth, BigDecimal> lastEquityByMonth = new LinkedHashMap<>();
    for (DailyEquityPoint point : curve) {
      lastEquityByMonth.put(YearMonth.from(point.day()), point.equity());
    }
    List<YearMonth> months = new ArrayList<>(lastEquityByMonth.keySet());
    List<MonthlyReturn> result = new ArrayList<>();
    for (int i = 1; i < months.size(); i++) {
      BigDecimal previous = lastEquityByMonth.get(months.get(i - 1));
      BigDecimal current = lastEquityByMonth.get(months.get(i));
      if (previous.signum() == 0) {
        continue;
      }
      BigDecimal returnPct =
          current
              .subtract(previous)
              .multiply(BigDecimal.valueOf(100))
              .divide(previous, 4, RoundingMode.HALF_UP);
      result.add(new MonthlyReturn(months.get(i).toString(), returnPct));
    }
    return result;
  }

  public record MonthlyReturn(String month, BigDecimal returnPct) {}
}
