package com.nectrix.coreapp.analytics.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. Reads
 * copy_relationships/copied_trades/account_snapshots/master_profiles directly via raw SQL (same
 * "read another module's table directly via SQL" precedent {@code trading}'s own
 * CopyRelationshipRepository already established by joining {@code master_profiles}) — see this
 * module's own build.gradle.kts comment for why that doesn't need a project() dependency.
 */
@Repository
public class LeaderboardComputationRepository {

  private final JdbcTemplate jdbcTemplate;

  public LeaderboardComputationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record MasterProfileRef(UUID id, UUID primaryBrokerAccountId) {}

  public List<MasterProfileRef> findAllMasterProfiles() {
    return jdbcTemplate.query(
        "SELECT id, primary_broker_account_id FROM master_profiles",
        (rs, rowNum) ->
            new MasterProfileRef(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("primary_broker_account_id"))));
  }

  public record DailyEquityPoint(LocalDate day, BigDecimal equity) {}

  /**
   * One row per day (the last real sample captured that day), oldest first — the "sampled daily by
   * default" resampling docs/09-money-management-risk-formulas.md §9.8 assumes for its formulas.
   * Empty if the account has no snapshots in the window at all.
   */
  public List<DailyEquityPoint> findDailyEquityCurve(UUID brokerAccountId, Instant periodStart) {
    return jdbcTemplate.query(
        """
        SELECT DISTINCT ON (date_trunc('day', captured_at))
               date_trunc('day', captured_at)::date AS day, equity
        FROM account_snapshots
        WHERE broker_account_id = ? AND captured_at >= ?
        ORDER BY date_trunc('day', captured_at), captured_at DESC
        """,
        (rs, rowNum) ->
            new DailyEquityPoint(rs.getDate("day").toLocalDate(), rs.getBigDecimal("equity")),
        brokerAccountId,
        java.sql.Timestamp.from(periodStart));
  }

  public record WinRateInput(long wins, long total) {}

  /**
   * From {@code copied_trades} attributable to this master (via {@code copy_relationships}) — see
   * LeaderboardComputationService's own Javadoc for why this, not the master's own trades directly
   * (trade_signals has no realized_pnl column).
   */
  public WinRateInput winRateInput(UUID masterProfileId, Instant periodStart) {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*) FILTER (WHERE ct.realized_pnl > 0) AS wins, count(*) AS total
        FROM copied_trades ct
        JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
        WHERE cr.master_profile_id = ? AND ct.status = 'CLOSED' AND ct.closed_at >= ?
        """,
        (rs, rowNum) -> new WinRateInput(rs.getLong("wins"), rs.getLong("total")),
        masterProfileId,
        java.sql.Timestamp.from(periodStart));
  }

  /** Currently-following count — ACTIVE and PAUSED both count as "following," STOPPED doesn't. */
  public int followerCount(UUID masterProfileId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM copy_relationships
            WHERE master_profile_id = ? AND status IN ('ACTIVE','PAUSED')
            """,
            Integer.class,
            masterProfileId);
    return count == null ? 0 : count;
  }

  /**
   * "Sum of follower equity currently copying this master" (docs/10-portfolio-social-trading.md
   * §10.2) — ACTIVE only (a paused follower isn't "currently copying"), latest known equity per
   * follower broker account.
   */
  public BigDecimal aumProxy(UUID masterProfileId) {
    BigDecimal sum =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(latest.equity), 0) FROM (
              SELECT DISTINCT ON (cr.follower_broker_account_id) a.equity
              FROM copy_relationships cr
              JOIN account_snapshots a ON a.broker_account_id = cr.follower_broker_account_id
              WHERE cr.master_profile_id = ? AND cr.status = 'ACTIVE'
              ORDER BY cr.follower_broker_account_id, a.captured_at DESC
            ) latest
            """,
            BigDecimal.class,
            masterProfileId);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  public void insertSnapshot(
      UUID masterProfileId,
      String period,
      BigDecimal returnPct,
      BigDecimal maxDrawdownPct,
      BigDecimal winRatePct,
      BigDecimal sharpeLikeRatio,
      int followerCount,
      BigDecimal aumProxy) {
    jdbcTemplate.update(
        """
        INSERT INTO leaderboard_snapshots
          (master_profile_id, period, return_pct, max_drawdown_pct, win_rate_pct,
           sharpe_like_ratio, follower_count, aum_proxy)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        masterProfileId,
        period,
        returnPct,
        maxDrawdownPct,
        winRatePct,
        sharpeLikeRatio,
        followerCount,
        aumProxy);
  }
}
