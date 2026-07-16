package com.nectrix.coreapp.social.repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * TICKET-112 — public discovery reads. Plain JDBC, no ORM. Reads {@code leaderboard_snapshots}
 * (written by {@code modules:analytics}' own scheduled job — see that module's own build.gradle.kts
 * comment for why this cross-"module" table read via raw SQL doesn't need a project() dependency)
 * joined to this module's own {@code master_profiles}.
 *
 * <p>The eligibility gate (docs/10-portfolio-social-trading.md §10.2's anti-gaming note: "e.g., 30
 * days / 20 closed trades") is applied only in {@link #findLeaderboard} (the ranked listing) —
 * {@link #findMasterPublicProfile} never filters on it, since a below-threshold master's own
 * profile page still shows its (thinner) metrics per that same doc section.
 */
@Repository
public class DiscoveryRepository {

  /** Whitelisted — never interpolate a caller-supplied sort key directly into SQL. */
  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "return_pct", "ls.return_pct DESC NULLS LAST",
          "max_drawdown_pct", "ls.max_drawdown_pct ASC NULLS LAST",
          "follower_count", "ls.follower_count DESC",
          "sharpe_like_ratio", "ls.sharpe_like_ratio DESC NULLS LAST");

  private static final Set<String> VALID_PERIODS = Set.of("7D", "30D", "90D", "YTD", "ALL");

  private final JdbcTemplate jdbcTemplate;

  public DiscoveryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record LeaderboardEntry(
      UUID masterProfileId,
      String displayName,
      List<String> strategyTags,
      BigDecimal returnPct,
      BigDecimal maxDrawdownPct,
      BigDecimal winRatePct,
      BigDecimal sharpeLikeRatio,
      int followerCount,
      BigDecimal aumProxy) {}

  private static final RowMapper<LeaderboardEntry> ENTRY_MAPPER =
      (rs, rowNum) -> {
        Array tagsArray = rs.getArray("strategy_tags");
        List<String> tags =
            tagsArray != null ? List.of((String[]) tagsArray.getArray()) : List.of();
        return new LeaderboardEntry(
            UUID.fromString(rs.getString("id")),
            rs.getString("display_name"),
            tags,
            rs.getBigDecimal("return_pct"),
            rs.getBigDecimal("max_drawdown_pct"),
            rs.getBigDecimal("win_rate_pct"),
            rs.getBigDecimal("sharpe_like_ratio"),
            rs.getInt("follower_count"),
            rs.getBigDecimal("aum_proxy"));
      };

  /**
   * @param period one of 7D/30D/90D/YTD/ALL — validated here (400-worthy caller input), not trusted
   *     as a safe SQL literal.
   * @param sort one of {@link #SORT_COLUMNS}' keys.
   */
  public List<LeaderboardEntry> findLeaderboard(
      String period, String sort, int page, int pageSize) {
    String orderBy = SORT_COLUMNS.getOrDefault(sort, SORT_COLUMNS.get("return_pct"));
    String validPeriod = VALID_PERIODS.contains(period) ? period : "30D";
    return jdbcTemplate.query(
        """
        SELECT mp.id, mp.display_name, mp.strategy_tags,
               ls.return_pct, ls.max_drawdown_pct, ls.win_rate_pct, ls.sharpe_like_ratio,
               ls.follower_count, ls.aum_proxy
        FROM master_profiles mp
        JOIN LATERAL (
          SELECT * FROM leaderboard_snapshots
          WHERE master_profile_id = mp.id AND period = ?
          ORDER BY computed_at DESC LIMIT 1
        ) ls ON true
        WHERE mp.is_public = true
          AND mp.created_at <= now() - interval '30 days'
          AND (
            SELECT count(*) FROM copied_trades ct
            JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
            WHERE cr.master_profile_id = mp.id AND ct.status = 'CLOSED'
          ) >= 20
        ORDER BY \
        """
            + orderBy
            + " LIMIT ? OFFSET ?",
        ENTRY_MAPPER,
        validPeriod,
        pageSize,
        page * pageSize);
  }

  public record MasterPublicProfile(
      UUID id,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod,
      Instant verifiedAt,
      Map<String, LeaderboardEntry> metricsByPeriod) {}

  public Optional<MasterPublicProfile> findMasterPublicProfile(UUID masterProfileId) {
    List<MasterPublicProfile> rows =
        jdbcTemplate.query(
            """
            SELECT id, display_name, bio, strategy_tags, performance_fee_percent,
                   fee_collection_method, verified_at
            FROM master_profiles WHERE id = ? AND is_public = true
            """,
            (RowMapper<MasterPublicProfile>)
                (rs, rowNum) -> {
                  Array tagsArray = rs.getArray("strategy_tags");
                  List<String> tags =
                      tagsArray != null ? List.of((String[]) tagsArray.getArray()) : List.of();
                  java.sql.Timestamp verifiedAt = rs.getTimestamp("verified_at");
                  return new MasterPublicProfile(
                      UUID.fromString(rs.getString("id")),
                      rs.getString("display_name"),
                      rs.getString("bio"),
                      tags,
                      rs.getBigDecimal("performance_fee_percent"),
                      rs.getString("fee_collection_method"),
                      verifiedAt != null ? verifiedAt.toInstant() : null,
                      Map.of());
                },
            masterProfileId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    MasterPublicProfile base = rows.get(0);
    Map<String, LeaderboardEntry> metrics = findAllPeriodMetrics(masterProfileId);
    return Optional.of(
        new MasterPublicProfile(
            base.id(),
            base.displayName(),
            base.bio(),
            base.strategyTags(),
            base.performanceFeePercent(),
            base.feeCollectionMethod(),
            base.verifiedAt(),
            metrics));
  }

  private Map<String, LeaderboardEntry> findAllPeriodMetrics(UUID masterProfileId) {
    List<Object[]> rows =
        jdbcTemplate.query(
            """
            SELECT DISTINCT ON (period) period, master_profile_id, return_pct, max_drawdown_pct,
                   win_rate_pct, sharpe_like_ratio, follower_count, aum_proxy
            FROM leaderboard_snapshots
            WHERE master_profile_id = ?
            ORDER BY period, computed_at DESC
            """,
            (RowMapper<Object[]>)
                (rs, rowNum) ->
                    new Object[] {
                      rs.getString("period"),
                      new LeaderboardEntry(
                          masterProfileId,
                          null,
                          List.of(),
                          rs.getBigDecimal("return_pct"),
                          rs.getBigDecimal("max_drawdown_pct"),
                          rs.getBigDecimal("win_rate_pct"),
                          rs.getBigDecimal("sharpe_like_ratio"),
                          rs.getInt("follower_count"),
                          rs.getBigDecimal("aum_proxy"))
                    },
            masterProfileId);
    Map<String, LeaderboardEntry> byPeriod = new java.util.HashMap<>();
    for (Object[] row : rows) {
      byPeriod.put((String) row[0], (LeaderboardEntry) row[1]);
    }
    return byPeriod;
  }
}
