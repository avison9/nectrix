package com.nectrix.coreapp.analytics.service;

import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.DailyEquityPoint;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.InstrumentPnl;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.OwnedMasterProfileRef;
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
import java.util.UUID;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

/**
 * TICKET-116 — authenticated, ownership-scoped per-master analytics: equity curve, monthly returns,
 * P&amp;L by instrument. Distinct from TICKET-112's public {@code DiscoveryController} leaderboard
 * endpoints (public, aggregate-only, batch-computed into {@code leaderboard_snapshots}) — this is
 * live-queried, and only the owning Master (or staff) can see it.
 */
@Service
public class MasterAnalyticsService {

  /** Same period vocabulary as {@code LeaderboardComputationService}'s own {@code PERIODS}. */
  private static final Map<String, Long> FIXED_WINDOW_DAYS =
      Map.of("7D", 7L, "30D", 30L, "90D", 90L);

  /** Trailing window wide enough to always cover a full 12 calendar months of monthly returns. */
  private static final long MONTHLY_RETURNS_LOOKBACK_DAYS = 395L;

  private final MasterAnalyticsRepository repository;
  private final LeaderboardComputationRepository leaderboardRepository;

  public MasterAnalyticsService(
      MasterAnalyticsRepository repository,
      LeaderboardComputationRepository leaderboardRepository) {
    this.repository = repository;
    this.leaderboardRepository = leaderboardRepository;
  }

  /**
   * Fetch-then-check-then-compute — same discipline {@code CopyRelationshipService}/{@code
   * MasterProfileService} already establish (self-invocation would bypass Spring AOP's
   * {@code @PostAuthorize} proxy, so the check can't live inside {@link #computeAnalytics} itself).
   */
  @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")
  public OwnedMasterProfileRef getOwnedMasterProfile(UUID id) {
    return repository.findOwnedMasterProfile(id).orElseThrow(MasterProfileNotFoundException::new);
  }

  public MasterAnalytics computeAnalytics(OwnedMasterProfileRef profile, String period) {
    Instant now = Instant.now();
    Instant periodStart = periodStart(period, now);

    List<DailyEquityPoint> equityCurve =
        leaderboardRepository.findDailyEquityCurve(profile.primaryBrokerAccountId(), periodStart);

    List<DailyEquityPoint> lookbackCurve =
        leaderboardRepository.findDailyEquityCurve(
            profile.primaryBrokerAccountId(),
            now.minus(MONTHLY_RETURNS_LOOKBACK_DAYS, ChronoUnit.DAYS));
    List<MonthlyReturn> monthlyReturns = computeMonthlyReturns(lookbackCurve);

    List<InstrumentPnl> pnlByInstrument = repository.pnlByInstrument(profile.id(), periodStart);

    return new MasterAnalytics(equityCurve, monthlyReturns, pnlByInstrument);
  }

  /**
   * Buckets the curve by calendar month (last known equity point of each month), then returns
   * month-over-month % change. The earliest month in the lookback window has no prior month to
   * compare against, so it's excluded from the output entirely — same "no meaningful zero row"
   * reasoning {@code LeaderboardComputationService} already applies to an empty curve.
   */
  private List<MonthlyReturn> computeMonthlyReturns(List<DailyEquityPoint> curve) {
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

  public record MonthlyReturn(String month, BigDecimal returnPct) {}

  public record MasterAnalytics(
      List<DailyEquityPoint> equityCurve,
      List<MonthlyReturn> monthlyReturns,
      List<InstrumentPnl> pnlByInstrument) {}
}
