package com.nectrix.coreapp.analytics.scheduler;

import com.nectrix.coreapp.analytics.service.LeaderboardComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly leaderboard recomputation — docs/12-analytics-notifications-admin.md §12.1's MVP cadence
 * ("Postgres materialized views... refreshed hourly for leaderboards"; this codebase's {@code
 * leaderboard_snapshots} is a plain append-only table rather than a real materialized view, but the
 * recompute cadence is the same). {@code @EnableScheduling} lives on {@code CoreAppApplication} —
 * this is the first scheduled job in this codebase.
 */
@Component
public class LeaderboardScheduler {

  private static final Logger log = LoggerFactory.getLogger(LeaderboardScheduler.class);

  private final LeaderboardComputationService service;

  public LeaderboardScheduler(LeaderboardComputationService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 * * * *")
  public void run() {
    try {
      service.computeAll();
    } catch (Exception e) {
      log.error("analytics: leaderboard computation run failed", e);
    }
  }
}
