package com.nectrix.coreapp.analytics.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Feature — the Follower-scoped counterpart to {@link MasterAnalyticsRepository}. A Follower can
 * follow many Masters (many {@code copy_relationships} rows, potentially on several of their own
 * broker accounts), so this is inherently an aggregation across all of them, not a single-profile
 * lookup — see {@code FollowerAnalyticsService} for how the equity curve gets summed per day across
 * every account this returns.
 */
@Repository
public class FollowerAnalyticsRepository {

  private final JdbcTemplate jdbcTemplate;

  public FollowerAnalyticsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Bugfix — every broker account behind a currently-ACTIVE/PAUSED relationship, matching the
   * dashboard's own scope ({@code FollowerDashboard.tsx} only sums live equity for these same two
   * statuses). Originally unfiltered (every relationship ever, including STOPPED), reasoning by a
   * false analogy to {@code pnlByInstrument} below: that method is correctly unfiltered by
   * relationship status because {@code ct.status='CLOSED'} is a trade-level fact that should
   * survive a stopped relationship (P&amp;L already earned doesn't un-happen), whereas an equity
   * curve is a "state right now" concept — including a STOPPED relationship's stale broker account
   * here is exactly what caused analytics' "current equity" to disagree with the dashboard's own
   * live figure for the same Follower.
   */
  public List<UUID> findFollowerBrokerAccountIds(UUID followerUserId) {
    return jdbcTemplate.queryForList(
        """
        SELECT DISTINCT follower_broker_account_id FROM copy_relationships
        WHERE follower_user_id = ? AND status IN ('ACTIVE','PAUSED')
        """,
        UUID.class,
        followerUserId);
  }

  /**
   * Same shape as {@code MasterAnalyticsRepository#pnlByInstrument}, scoped by {@code
   * cr.follower_user_id} instead of {@code cr.master_profile_id} — same joins, since {@code
   * copy_relationships} already carries both sides.
   */
  public List<MasterAnalyticsRepository.InstrumentPnl> pnlByInstrument(
      UUID followerUserId, Instant periodStart) {
    return jdbcTemplate.query(
        """
        SELECT ts.canonical_symbol AS symbol,
               COALESCE(SUM(ct.realized_pnl), 0) AS total_pnl,
               count(*) AS trade_count
        FROM copied_trades ct
        JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
        JOIN trade_signals ts ON ts.id = ct.trade_signal_id
        WHERE cr.follower_user_id = ? AND ct.status = 'CLOSED' AND ct.closed_at >= ?
        GROUP BY ts.canonical_symbol
        ORDER BY total_pnl DESC
        """,
        (rs, rowNum) ->
            new MasterAnalyticsRepository.InstrumentPnl(
                rs.getString("symbol"), rs.getBigDecimal("total_pnl"), rs.getLong("trade_count")),
        followerUserId,
        Timestamp.from(periodStart));
  }
}
