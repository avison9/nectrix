package com.nectrix.coreapp.analytics.service;

import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository;
import com.nectrix.coreapp.analytics.repository.LeaderboardComputationRepository.DailyEquityPoint;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.InstrumentPnl;
import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.OwnedMasterProfileRef;
import com.nectrix.coreapp.analytics.service.AnalyticsPeriods.MonthlyReturn;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    Instant periodStart = AnalyticsPeriods.periodStart(period, now);

    List<DailyEquityPoint> equityCurve =
        leaderboardRepository.findDailyEquityCurve(profile.primaryBrokerAccountId(), periodStart);

    List<DailyEquityPoint> lookbackCurve =
        leaderboardRepository.findDailyEquityCurve(
            profile.primaryBrokerAccountId(),
            now.minus(AnalyticsPeriods.MONTHLY_RETURNS_LOOKBACK_DAYS, ChronoUnit.DAYS));
    List<MonthlyReturn> monthlyReturns = AnalyticsPeriods.computeMonthlyReturns(lookbackCurve);

    List<InstrumentPnl> pnlByInstrument = repository.pnlByInstrument(profile.id(), periodStart);

    return new MasterAnalytics(equityCurve, monthlyReturns, pnlByInstrument);
  }

  public record MasterAnalytics(
      List<DailyEquityPoint> equityCurve,
      List<MonthlyReturn> monthlyReturns,
      List<InstrumentPnl> pnlByInstrument) {}
}
