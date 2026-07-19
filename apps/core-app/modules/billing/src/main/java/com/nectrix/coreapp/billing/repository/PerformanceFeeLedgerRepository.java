package com.nectrix.coreapp.billing.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * TICKET-113 — docs/11-fee-engine-billing.md §11.4: settlement runs are idempotent per {@code
 * (copy_relationship_id, period_start, period_end)}, enforced by the table's own {@code UNIQUE}
 * constraint (007-billing.sql) — {@link #tryInsert} relies on that constraint catching a duplicate
 * at the DB level (AC4 wants this proven at that level, not just an application-side pre-check that
 * could race).
 */
@Repository
public class PerformanceFeeLedgerRepository {

  private final JdbcTemplate jdbcTemplate;

  public PerformanceFeeLedgerRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<Instant> findLastPeriodEnd(UUID copyRelationshipId) {
    return jdbcTemplate
        .query(
            """
            SELECT period_end FROM performance_fee_ledger
            WHERE copy_relationship_id = ? ORDER BY period_end DESC LIMIT 1
            """,
            (rs, rowNum) -> rs.getTimestamp("period_end").toInstant(),
            copyRelationshipId)
        .stream()
        .findFirst();
  }

  public boolean hasFinalSettlementAt(UUID copyRelationshipId, Instant periodEnd) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM performance_fee_ledger WHERE copy_relationship_id = ? AND period_end = ?",
            Integer.class,
            copyRelationshipId,
            Timestamp.from(periodEnd));
    return count != null && count > 0;
  }

  /**
   * @return the new row's id, or empty if this exact period was already settled (unique constraint
   *     violation — a genuine idempotent no-op, not an error).
   */
  public Optional<UUID> tryInsert(
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal startingHwm,
      BigDecimal endingEquity,
      BigDecimal newProfitAboveHwm,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String computationDetailJson) {
    try {
      UUID id =
          jdbcTemplate.execute(
              """
              INSERT INTO performance_fee_ledger
                (copy_relationship_id, period_start, period_end, starting_hwm, ending_equity,
                 new_profit_above_hwm, master_fee_amount, platform_take_amount, net_to_master_amount,
                 computation_detail)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
              RETURNING id
              """,
              (PreparedStatement ps) -> {
                int i = 1;
                ps.setObject(i++, copyRelationshipId);
                ps.setTimestamp(i++, Timestamp.from(periodStart));
                ps.setTimestamp(i++, Timestamp.from(periodEnd));
                ps.setBigDecimal(i++, startingHwm);
                ps.setBigDecimal(i++, endingEquity);
                ps.setBigDecimal(i++, newProfitAboveHwm);
                ps.setBigDecimal(i++, masterFeeAmount);
                ps.setBigDecimal(i++, platformTakeAmount);
                ps.setBigDecimal(i++, netToMasterAmount);
                ps.setString(i, computationDetailJson);
                try (ResultSet rs = ps.executeQuery()) {
                  rs.next();
                  return UUID.fromString(rs.getString(1));
                }
              });
      return Optional.ofNullable(id);
    } catch (DataIntegrityViolationException alreadySettled) {
      return Optional.empty();
    }
  }

  public void updateStatus(UUID ledgerId, String status) {
    jdbcTemplate.update(
        "UPDATE performance_fee_ledger SET status = ? WHERE id = ?", status, ledgerId);
  }

  public record LedgerRow(
      UUID id, UUID copyRelationshipId, BigDecimal masterFeeAmount, String status) {}

  public Optional<LedgerRow> findById(UUID id) {
    return jdbcTemplate
        .query(
            "SELECT id, copy_relationship_id, master_fee_amount, status FROM performance_fee_ledger WHERE id = ?",
            (rs, rowNum) ->
                new LedgerRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("copy_relationship_id")),
                    rs.getBigDecimal("master_fee_amount"),
                    rs.getString("status")),
            id)
        .stream()
        .findFirst();
  }

  /**
   * TICKET-117 — the Disputes list view. Newest period first, same convention as
   * UserRepository#search.
   */
  public List<LedgerSummary> findByStatus(String status, int page, int pageSize) {
    return jdbcTemplate.query(
        """
        SELECT id, copy_relationship_id, period_start, period_end, master_fee_amount,
               platform_take_amount, net_to_master_amount, status
        FROM performance_fee_ledger
        WHERE status = ?
        ORDER BY period_end DESC
        LIMIT ? OFFSET ?
        """,
        LEDGER_SUMMARY_MAPPER,
        status,
        pageSize,
        page * pageSize);
  }

  public record LedgerSummary(
      UUID id,
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String status) {}

  private static final RowMapper<LedgerSummary> LEDGER_SUMMARY_MAPPER =
      (rs, rowNum) ->
          new LedgerSummary(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("copy_relationship_id")),
              rs.getTimestamp("period_start").toInstant(),
              rs.getTimestamp("period_end").toInstant(),
              rs.getBigDecimal("master_fee_amount"),
              rs.getBigDecimal("platform_take_amount"),
              rs.getBigDecimal("net_to_master_amount"),
              rs.getString("status"));

  /**
   * TICKET-117 — the Dispute detail view. {@code computationDetailJson} is the raw JSONB text,
   * self-contained by design (see class Javadoc's own reference to SettlementComputation) — callers
   * render it as a real line-item breakdown, not a passthrough JSON dump.
   */
  public record LedgerDetail(
      UUID id,
      UUID copyRelationshipId,
      Instant periodStart,
      Instant periodEnd,
      BigDecimal startingHwm,
      BigDecimal endingEquity,
      BigDecimal newProfitAboveHwm,
      BigDecimal masterFeeAmount,
      BigDecimal platformTakeAmount,
      BigDecimal netToMasterAmount,
      String computationDetailJson,
      String status) {}

  public Optional<LedgerDetail> findDetailById(UUID id) {
    return jdbcTemplate
        .query(
            """
            SELECT id, copy_relationship_id, period_start, period_end, starting_hwm, ending_equity,
                   new_profit_above_hwm, master_fee_amount, platform_take_amount,
                   net_to_master_amount, computation_detail::text AS computation_detail, status
            FROM performance_fee_ledger WHERE id = ?
            """,
            (rs, rowNum) ->
                new LedgerDetail(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("copy_relationship_id")),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getBigDecimal("starting_hwm"),
                    rs.getBigDecimal("ending_equity"),
                    rs.getBigDecimal("new_profit_above_hwm"),
                    rs.getBigDecimal("master_fee_amount"),
                    rs.getBigDecimal("platform_take_amount"),
                    rs.getBigDecimal("net_to_master_amount"),
                    rs.getString("computation_detail"),
                    rs.getString("status")),
            id)
        .stream()
        .findFirst();
  }

  /**
   * TICKET-117 — the underlying {@code copied_trades} in a disputed period, joined to {@code
   * trade_signals} for the symbol/direction the ledger's own rows don't carry directly (see
   * 006-copy-trading.sql). Windowed on {@code opened_at}, matching how a settlement period is
   * actually defined (docs/11-fee-engine-billing.md).
   */
  public record UnderlyingTrade(
      UUID id,
      String canonicalSymbol,
      String direction,
      BigDecimal computedVolumeLots,
      String status,
      BigDecimal realizedPnl,
      Instant openedAt,
      Instant closedAt) {}

  /**
   * TICKET-117 follow-up — self-service settlement history for a Master or Follower, "either party"
   * (the same query serves both roles without needing a role param: a caller sees rows where
   * they're the follower directly, or the master via {@code master_profiles} ownership — same join
   * shape {@code CopyRelationshipRepository#findAllForUser} already establishes for {@code
   * copy_relationships} itself).
   */
  public List<LedgerSummary> findAllForUser(UUID userId, int page, int pageSize) {
    return jdbcTemplate.query(
        """
        SELECT pfl.id, pfl.copy_relationship_id, pfl.period_start, pfl.period_end,
               pfl.master_fee_amount, pfl.platform_take_amount, pfl.net_to_master_amount, pfl.status
        FROM performance_fee_ledger pfl
        JOIN copy_relationships cr ON cr.id = pfl.copy_relationship_id
        JOIN master_profiles mp ON mp.id = cr.master_profile_id
        WHERE cr.follower_user_id = ? OR mp.user_id = ?
        ORDER BY pfl.period_end DESC
        LIMIT ? OFFSET ?
        """,
        LEDGER_SUMMARY_MAPPER,
        userId,
        userId,
        pageSize,
        page * pageSize);
  }

  /**
   * Same ownership shape as {@link #findAllForUser} — empty if the row exists but isn't the
   * caller's.
   */
  public Optional<LedgerDetail> findDetailForUser(UUID ledgerId, UUID userId) {
    return jdbcTemplate
        .query(
            """
            SELECT pfl.id, pfl.copy_relationship_id, pfl.period_start, pfl.period_end,
                   pfl.starting_hwm, pfl.ending_equity, pfl.new_profit_above_hwm,
                   pfl.master_fee_amount, pfl.platform_take_amount, pfl.net_to_master_amount,
                   pfl.computation_detail::text AS computation_detail, pfl.status
            FROM performance_fee_ledger pfl
            JOIN copy_relationships cr ON cr.id = pfl.copy_relationship_id
            JOIN master_profiles mp ON mp.id = cr.master_profile_id
            WHERE pfl.id = ? AND (cr.follower_user_id = ? OR mp.user_id = ?)
            """,
            (rs, rowNum) ->
                new LedgerDetail(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("copy_relationship_id")),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getBigDecimal("starting_hwm"),
                    rs.getBigDecimal("ending_equity"),
                    rs.getBigDecimal("new_profit_above_hwm"),
                    rs.getBigDecimal("master_fee_amount"),
                    rs.getBigDecimal("platform_take_amount"),
                    rs.getBigDecimal("net_to_master_amount"),
                    rs.getString("computation_detail"),
                    rs.getString("status")),
            ledgerId,
            userId,
            userId)
        .stream()
        .findFirst();
  }

  public List<UnderlyingTrade> findUnderlyingTrades(
      UUID copyRelationshipId, Instant periodStart, Instant periodEnd) {
    return jdbcTemplate.query(
        """
        SELECT ct.id, ts.canonical_symbol, ts.direction, ct.computed_volume_lots, ct.status,
               ct.realized_pnl, ct.opened_at, ct.closed_at
        FROM copied_trades ct
        JOIN trade_signals ts ON ts.id = ct.trade_signal_id
        WHERE ct.copy_relationship_id = ?
          AND ct.opened_at >= ? AND ct.opened_at < ?
        ORDER BY ct.opened_at
        """,
        (rs, rowNum) ->
            new UnderlyingTrade(
                UUID.fromString(rs.getString("id")),
                rs.getString("canonical_symbol"),
                rs.getString("direction"),
                rs.getBigDecimal("computed_volume_lots"),
                rs.getString("status"),
                rs.getBigDecimal("realized_pnl"),
                rs.getTimestamp("opened_at") == null
                    ? null
                    : rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("closed_at") == null
                    ? null
                    : rs.getTimestamp("closed_at").toInstant()),
        copyRelationshipId,
        Timestamp.from(periodStart),
        Timestamp.from(periodEnd));
  }
}
