package com.nectrix.coreapp.analytics.service;

import com.nectrix.coreapp.analytics.repository.FollowerAnalyticsRepository;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.DailyEquityPoint;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.InstrumentPnl;
import com.nectrix.coreapp.analytics.service.AnalyticsPeriods.MonthlyReturn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Feature — the Follower-facing counterpart to {@link MasterAnalyticsService}. Unlike a Master (one
 * profile, one {@code primary_broker_account_id}), a Follower can copy from many Masters —
 * potentially through several of their own broker accounts — so this is inherently scoped to "every
 * account this Follower has ever copied onto," aggregated, not a single-account lookup. Inherently
 * self-scoped by {@code followerUserId} (from the caller's own JWT, same convention {@code
 * CopyRelationshipController#list}'s {@code role=follower} already uses) — no
 * {@code @PostAuthorize} needed, unlike Master analytics' per-{@code id} route.
 */
@Service
public class FollowerAnalyticsService {

  private final FollowerAnalyticsRepository repository;
  private final LeaderboardComputationRepository leaderboardRepository;

  public FollowerAnalyticsService(
      FollowerAnalyticsRepository repository,
      LeaderboardComputationRepository leaderboardRepository) {
    this.repository = repository;
    this.leaderboardRepository = leaderboardRepository;
  }

  public FollowerAnalytics computeAnalytics(UUID followerUserId, String period) {
    Instant now = Instant.now();
    Instant periodStart = AnalyticsPeriods.periodStart(period, now);
    List<UUID> brokerAccountIds = repository.findFollowerBrokerAccountIds(followerUserId);

    List<DailyEquityPoint> equityCurve = mergedEquityCurve(brokerAccountIds, periodStart);
    List<DailyEquityPoint> lookbackCurve =
        mergedEquityCurve(
            brokerAccountIds,
            now.minus(AnalyticsPeriods.MONTHLY_RETURNS_LOOKBACK_DAYS, ChronoUnit.DAYS));
    List<MonthlyReturn> monthlyReturns = AnalyticsPeriods.computeMonthlyReturns(lookbackCurve);

    List<InstrumentPnl> pnlByInstrument = repository.pnlByInstrument(followerUserId, periodStart);

    return new FollowerAnalytics(equityCurve, monthlyReturns, pnlByInstrument);
  }

  /**
   * A Follower's equity across ALL their broker accounts, summed per calendar day — {@code
   * LeaderboardComputationRepository#findDailyEquityCurve} itself is per-account (a Master only
   * ever has one primary account, so it never needed to merge); this is the one new piece of logic
   * Follower analytics genuinely needs beyond straight reuse.
   */
  private List<DailyEquityPoint> mergedEquityCurve(
      List<UUID> brokerAccountIds, Instant periodStart) {
    if (brokerAccountIds.isEmpty()) {
      return List.of();
    }
    Map<LocalDate, BigDecimal> sumByDay = new TreeMap<>();
    for (UUID brokerAccountId : brokerAccountIds) {
      for (DailyEquityPoint point :
          leaderboardRepository.findDailyEquityCurve(brokerAccountId, periodStart)) {
        sumByDay.merge(point.day(), point.equity(), BigDecimal::add);
      }
    }
    return sumByDay.entrySet().stream()
        .map(e -> new DailyEquityPoint(e.getKey(), e.getValue()))
        .toList();
  }

  public record FollowerAnalytics(
      List<DailyEquityPoint> equityCurve,
      List<MonthlyReturn> monthlyReturns,
      List<InstrumentPnl> pnlByInstrument) {}
}
