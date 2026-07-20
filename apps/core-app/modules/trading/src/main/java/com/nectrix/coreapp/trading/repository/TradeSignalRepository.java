package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.TradeSignal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * TICKET-101 follow-up — {@code trade_signals} previously had no dedicated repository (only ever
 * inserted by {@code bootstrap}'s Kafka consumer via raw JdbcTemplate, read only via a join inside
 * {@link CopiedTradeRepository}). The archival flow is the first caller that needs to read/delete a
 * Master's own signal history directly.
 */
@Repository
public class TradeSignalRepository {

  private static final RowMapper<TradeSignal> ROW_MAPPER =
      (rs, rowNum) ->
          new TradeSignal(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("master_broker_account_id")),
              rs.getString("broker_position_id"),
              rs.getString("event_type"),
              rs.getString("canonical_symbol"),
              rs.getString("direction"),
              rs.getBigDecimal("volume_lots"),
              rs.getBigDecimal("closed_volume_lots"),
              rs.getBigDecimal("fill_price"),
              rs.getBigDecimal("sl_price"),
              rs.getBigDecimal("tp_price"),
              rs.getTimestamp("server_timestamp").toInstant(),
              rs.getTimestamp("received_at_gateway").toInstant(),
              rs.getString("raw_payload"));

  private final JdbcTemplate jdbcTemplate;

  public TradeSignalRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Only ever non-empty if this account was a real Master at some point. */
  public List<TradeSignal> findAllByMasterBrokerAccountId(UUID masterBrokerAccountId) {
    return jdbcTemplate.query(
        """
        SELECT id, master_broker_account_id, broker_position_id, event_type, canonical_symbol,
               direction, volume_lots, closed_volume_lots, fill_price, sl_price, tp_price,
               server_timestamp, received_at_gateway, raw_payload::text AS raw_payload
        FROM trade_signals WHERE master_broker_account_id = ?
        """,
        ROW_MAPPER,
        masterBrokerAccountId);
  }

  /**
   * Must run AFTER every {@code copied_trades} row referencing one of these signals is already gone
   * (see {@code CopyRelationshipArchivalApiImpl}'s own ordering — {@code
   * copied_trades.trade_signal_id} has no {@code ON DELETE CASCADE}).
   */
  public void deleteByMasterBrokerAccountId(UUID masterBrokerAccountId) {
    jdbcTemplate.update(
        "DELETE FROM trade_signals WHERE master_broker_account_id = ?", masterBrokerAccountId);
  }
}
