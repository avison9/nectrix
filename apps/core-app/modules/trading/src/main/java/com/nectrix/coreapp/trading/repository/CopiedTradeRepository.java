package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.CopiedTrade;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read-only — backs {@code GET /copy-relationships/{id}/trades} (TICKET-111) and {@code GET
 * /copy-relationships/trades} (TICKET-116's cross-relationship, filterable trade history).
 */
@Repository
public class CopiedTradeRepository {

  /**
   * {@code canonical_symbol} isn't a {@code copied_trades} column — every query here joins {@code
   * trade_signals} to pull it in (see {@link CopiedTrade}'s own Javadoc).
   */
  private static final String SELECT_COLUMNS =
      """
      ct.id, ct.copy_relationship_id, ct.trade_signal_id, ct.status, ts.canonical_symbol,
      ct.computed_volume_lots, ct.requested_price, ct.filled_price, ct.slippage_pips,
      ct.reject_reason, ct.realized_pnl, ct.opened_at, ct.closed_at, ct.created_at
      """;

  private static final RowMapper<CopiedTrade> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp openedAt = rs.getTimestamp("opened_at");
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new CopiedTrade(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("copy_relationship_id")),
            UUID.fromString(rs.getString("trade_signal_id")),
            rs.getString("status"),
            rs.getString("canonical_symbol"),
            rs.getBigDecimal("computed_volume_lots"),
            rs.getBigDecimal("requested_price"),
            rs.getBigDecimal("filled_price"),
            rs.getBigDecimal("slippage_pips"),
            rs.getString("reject_reason"),
            rs.getBigDecimal("realized_pnl"),
            openedAt != null ? openedAt.toInstant() : null,
            closedAt != null ? closedAt.toInstant() : null,
            rs.getTimestamp("created_at").toInstant());
      };

  private final JdbcTemplate jdbcTemplate;

  public CopiedTradeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Newest-first, matching AuditLogRepository's own pagination convention. {@code page} is
   * 0-indexed.
   */
  public List<CopiedTrade> findPage(UUID copyRelationshipId, int page, int pageSize) {
    return jdbcTemplate.query(
        "SELECT "
            + SELECT_COLUMNS
            + """
            FROM copied_trades ct
            JOIN trade_signals ts ON ts.id = ct.trade_signal_id
            WHERE ct.copy_relationship_id = ?
            ORDER BY ct.created_at DESC
            LIMIT ? OFFSET ?
            """,
        ROW_MAPPER,
        copyRelationshipId,
        pageSize,
        page * pageSize);
  }

  public long count(UUID copyRelationshipId) {
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM copied_trades WHERE copy_relationship_id = ?",
            Long.class,
            copyRelationshipId);
    return total == null ? 0 : total;
  }

  /**
   * TICKET-116 — cross-relationship trade history, scoped to every {@code copy_relationships} row
   * the caller owns (same {@code role="follower"|"master"} ownership shape as {@code
   * CopyRelationshipRepository.findAllForUser}), with optional symbol/date-range/status/single-
   * relationship filters. {@code page} is 0-indexed.
   */
  public List<CopiedTrade> findPageForUser(
      UUID userId,
      String role,
      String symbol,
      Instant from,
      Instant to,
      String status,
      UUID relationshipId,
      int page,
      int pageSize) {
    Filter filter = buildFilter(userId, role, symbol, from, to, status, relationshipId);
    List<Object> params = new ArrayList<>(filter.params());
    params.add(pageSize);
    params.add(page * pageSize);
    return jdbcTemplate.query(
        "SELECT "
            + SELECT_COLUMNS
            + filter.fromWhere()
            + " ORDER BY ct.created_at DESC LIMIT ? OFFSET ?",
        ROW_MAPPER,
        params.toArray());
  }

  public long countForUser(
      UUID userId,
      String role,
      String symbol,
      Instant from,
      Instant to,
      String status,
      UUID relationshipId) {
    Filter filter = buildFilter(userId, role, symbol, from, to, status, relationshipId);
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) " + filter.fromWhere(), Long.class, filter.params().toArray());
    return total == null ? 0 : total;
  }

  private Filter buildFilter(
      UUID userId,
      String role,
      String symbol,
      Instant from,
      Instant to,
      String status,
      UUID relationshipId) {
    StringBuilder sql = new StringBuilder();
    List<Object> params = new ArrayList<>();
    sql.append("FROM copied_trades ct ")
        .append("JOIN trade_signals ts ON ts.id = ct.trade_signal_id ")
        .append("JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id ");
    if ("master".equals(role)) {
      sql.append("JOIN master_profiles mp ON mp.id = cr.master_profile_id ")
          .append("WHERE mp.user_id = ? ");
    } else {
      sql.append("WHERE cr.follower_user_id = ? ");
    }
    params.add(userId);
    if (symbol != null) {
      sql.append("AND ts.canonical_symbol = ? ");
      params.add(symbol);
    }
    if (from != null) {
      sql.append("AND ct.created_at >= ? ");
      params.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append("AND ct.created_at <= ? ");
      params.add(Timestamp.from(to));
    }
    if (status != null) {
      sql.append("AND ct.status = ? ");
      params.add(status);
    }
    if (relationshipId != null) {
      sql.append("AND ct.copy_relationship_id = ? ");
      params.add(relationshipId);
    }
    return new Filter(sql.toString(), params);
  }

  private record Filter(String fromWhere, List<Object> params) {}

  /**
   * TICKET-101 follow-up — full, unpaginated export for the archival flow (as opposed to {@link
   * #findPage}'s UI-facing pagination) — every copied trade tied to any of the given relationships,
   * regardless of status.
   */
  public List<CopiedTrade> findAllForRelationshipIds(List<UUID> copyRelationshipIds) {
    if (copyRelationshipIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", copyRelationshipIds.stream().map(id -> "?").toList());
    return jdbcTemplate.query(
        "SELECT "
            + SELECT_COLUMNS
            + "FROM copied_trades ct JOIN trade_signals ts ON ts.id = ct.trade_signal_id "
            + "WHERE ct.copy_relationship_id IN ("
            + placeholders
            + ")",
        ROW_MAPPER,
        copyRelationshipIds.toArray());
  }

  /**
   * Must run BEFORE {@code trade_signals}/{@code copy_relationships} are deleted for the same
   * relationships — this table has no {@code ON DELETE CASCADE} from either parent (see {@code
   * CopyRelationshipArchivalApiImpl}'s own ordering).
   */
  public void deleteForRelationshipIds(List<UUID> copyRelationshipIds) {
    if (copyRelationshipIds.isEmpty()) {
      return;
    }
    String placeholders = String.join(",", copyRelationshipIds.stream().map(id -> "?").toList());
    jdbcTemplate.update(
        "DELETE FROM copied_trades WHERE copy_relationship_id IN (" + placeholders + ")",
        copyRelationshipIds.toArray());
  }
}
