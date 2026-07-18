package com.nectrix.coreapp.analytics.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-116 — Master Analytics' own ownership lookup + P&L-by-instrument aggregation. Same "read
 * another module's table directly via raw SQL, no project() dependency" convention {@link
 * LeaderboardComputationRepository} already established for this module (see this module's own
 * build.gradle.kts comment) — the equity curve itself is reused directly from that repository, not
 * duplicated here.
 */
@Repository
public class MasterAnalyticsRepository {

  private final JdbcTemplate jdbcTemplate;

  public MasterAnalyticsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * {@code userId} is what {@code MasterAnalyticsService#getOwnedMasterProfile}'s
   * {@code @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")} checks
   * against — same ownership shape {@code MasterProfileService#getMasterProfile} uses, re-derived
   * here via raw SQL rather than a cross-module {@code social.service} import (this module's own
   * convention).
   */
  public record OwnedMasterProfileRef(UUID id, UUID userId, UUID primaryBrokerAccountId) {}

  public Optional<OwnedMasterProfileRef> findOwnedMasterProfile(UUID masterProfileId) {
    return jdbcTemplate
        .query(
            "SELECT id, user_id, primary_broker_account_id FROM master_profiles WHERE id = ?",
            (rs, rowNum) ->
                new OwnedMasterProfileRef(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("user_id")),
                    UUID.fromString(rs.getString("primary_broker_account_id"))),
            masterProfileId)
        .stream()
        .findFirst();
  }

  public record InstrumentPnl(String canonicalSymbol, BigDecimal totalPnl, long tradeCount) {}

  /**
   * Closed, follower-executed trades attributable to this master (same {@code copied_trades} join
   * shape {@code LeaderboardComputationRepository#winRateInput} already uses for win rate), grouped
   * by the underlying {@code trade_signals.canonical_symbol} — {@code copied_trades} itself has no
   * symbol column (see {@code trading.domain.CopiedTrade}'s own Javadoc). Highest total P&L first.
   */
  public List<InstrumentPnl> pnlByInstrument(UUID masterProfileId, Instant periodStart) {
    return jdbcTemplate.query(
        """
        SELECT ts.canonical_symbol AS symbol,
               COALESCE(SUM(ct.realized_pnl), 0) AS total_pnl,
               count(*) AS trade_count
        FROM copied_trades ct
        JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
        JOIN trade_signals ts ON ts.id = ct.trade_signal_id
        WHERE cr.master_profile_id = ? AND ct.status = 'CLOSED' AND ct.closed_at >= ?
        GROUP BY ts.canonical_symbol
        ORDER BY total_pnl DESC
        """,
        (rs, rowNum) ->
            new InstrumentPnl(
                rs.getString("symbol"), rs.getBigDecimal("total_pnl"), rs.getLong("trade_count")),
        masterProfileId,
        Timestamp.from(periodStart));
  }
}
