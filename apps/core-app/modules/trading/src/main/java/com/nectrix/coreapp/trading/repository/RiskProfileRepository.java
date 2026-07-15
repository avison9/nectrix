package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.RiskProfile;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app (TICKET-103/104). */
@Repository
public class RiskProfileRepository {

  private static final RowMapper<RiskProfile> ROW_MAPPER =
      (rs, rowNum) ->
          new RiskProfile(
              UUID.fromString(rs.getString("id")),
              rs.getBigDecimal("max_lot_per_trade"),
              (Integer)
                  rs.getObject(
                      "max_open_positions"), // nullable INTEGER; getObject avoids 0-on-null
              rs.getBigDecimal("max_exposure_per_symbol_lots"),
              rs.getBigDecimal("max_total_exposure_lots"),
              rs.getBigDecimal("max_slippage_pips"),
              rs.getBigDecimal("drawdown_pause_pct"),
              rs.getBigDecimal("drawdown_close_all_pct"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbcTemplate;

  public RiskProfileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<RiskProfile> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM risk_profiles WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }

  /**
   * Inserts a new row. {@code maxSlippagePips} may be {@code null}, in which case {@code
   * COALESCE(?, 5)} mirrors the column's own {@code DEFAULT 5}. {@code drawdown_pause_pct}/{@code
   * drawdown_close_all_pct} are always inserted NULL — TICKET-108's job to populate.
   */
  public UUID insert(
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxExposurePerSymbolLots,
      BigDecimal maxTotalExposureLots,
      BigDecimal maxSlippagePips) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO risk_profiles
          (max_lot_per_trade, max_open_positions, max_exposure_per_symbol_lots,
           max_total_exposure_lots, max_slippage_pips)
        VALUES (?, ?, ?, ?, COALESCE(?, 5))
        RETURNING id
        """,
        UUID.class,
        maxLotPerTrade,
        maxOpenPositions,
        maxExposurePerSymbolLots,
        maxTotalExposureLots,
        maxSlippagePips);
  }

  /** Full-row update of this ticket's own columns. Returns rows updated (0 if id doesn't exist). */
  public int update(
      UUID id,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxExposurePerSymbolLots,
      BigDecimal maxTotalExposureLots,
      BigDecimal maxSlippagePips) {
    return jdbcTemplate.update(
        """
        UPDATE risk_profiles
        SET max_lot_per_trade = ?, max_open_positions = ?, max_exposure_per_symbol_lots = ?,
            max_total_exposure_lots = ?, max_slippage_pips = COALESCE(?, 5)
        WHERE id = ?
        """,
        maxLotPerTrade,
        maxOpenPositions,
        maxExposurePerSymbolLots,
        maxTotalExposureLots,
        maxSlippagePips,
        id);
  }

  /**
   * {@code copy_relationships.risk_profile_id} is NOT NULL — deleting a still-referenced row fails.
   */
  public int delete(UUID id) {
    return jdbcTemplate.update("DELETE FROM risk_profiles WHERE id = ?", id);
  }
}
