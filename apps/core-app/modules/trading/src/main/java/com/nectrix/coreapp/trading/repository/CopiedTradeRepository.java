package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.CopiedTrade;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read-only — backs {@code GET /copy-relationships/{id}/trades} (TICKET-111's trades-history view).
 */
@Repository
public class CopiedTradeRepository {

  private static final RowMapper<CopiedTrade> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp openedAt = rs.getTimestamp("opened_at");
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new CopiedTrade(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("copy_relationship_id")),
            UUID.fromString(rs.getString("trade_signal_id")),
            rs.getString("status"),
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
        """
        SELECT * FROM copied_trades
        WHERE copy_relationship_id = ?
        ORDER BY created_at DESC
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
}
