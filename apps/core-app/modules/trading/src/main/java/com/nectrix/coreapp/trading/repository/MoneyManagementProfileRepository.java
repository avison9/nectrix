package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.MoneyManagementProfile;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app (TICKET-103). */
@Repository
public class MoneyManagementProfileRepository {

  private static final RowMapper<MoneyManagementProfile> ROW_MAPPER =
      (rs, rowNum) ->
          new MoneyManagementProfile(
              UUID.fromString(rs.getString("id")),
              rs.getString("method"),
              rs.getBigDecimal("fixed_lot_size"),
              rs.getBigDecimal("multiplier"),
              rs.getBigDecimal("risk_percent"),
              rs.getString("custom_formula_expr"),
              rs.getString("rounding_mode"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbcTemplate;

  public MoneyManagementProfileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<MoneyManagementProfile> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM money_management_profiles WHERE id = ?", ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  /**
   * Inserts a new row and returns its generated id. {@code roundingMode} may be {@code null}, in
   * which case {@code COALESCE(?, 'DOWN')} mirrors the column's own {@code DEFAULT 'DOWN'} exactly
   * — a caller that omits it gets the same behavior whether or not it explicitly passes {@code
   * "DOWN"}.
   */
  public UUID insert(
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String customFormulaExpr,
      String roundingMode) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO money_management_profiles
          (method, fixed_lot_size, multiplier, risk_percent, custom_formula_expr, rounding_mode)
        VALUES (?, ?, ?, ?, ?, COALESCE(?, 'DOWN'))
        RETURNING id
        """,
        UUID.class,
        method,
        fixedLotSize,
        multiplier,
        riskPercent,
        customFormulaExpr,
        roundingMode);
  }

  /** Full-row update. Returns the number of rows updated (0 if no row exists for {@code id}). */
  public int update(
      UUID id,
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String customFormulaExpr,
      String roundingMode) {
    return jdbcTemplate.update(
        """
        UPDATE money_management_profiles
        SET method = ?, fixed_lot_size = ?, multiplier = ?, risk_percent = ?,
            custom_formula_expr = ?, rounding_mode = COALESCE(?, 'DOWN')
        WHERE id = ?
        """,
        method,
        fixedLotSize,
        multiplier,
        riskPercent,
        customFormulaExpr,
        roundingMode,
        id);
  }

  /**
   * Deletes a profile row. In practice a profile is expected to be superseded via {@link #update},
   * not delete+recreate, given the 1:1, no-template-reuse cardinality with {@code
   * copy_relationships} (nectrix_plan/docs/06-database-schema.md's {@code ||--||} ERD notation) —
   * included here for CRUD symmetry. Postgres's {@code NOT NULL} FK from {@code
   * copy_relationships.money_management_profile_id} rejects any delete attempted while a
   * relationship still references this row.
   */
  public int delete(UUID id) {
    return jdbcTemplate.update("DELETE FROM money_management_profiles WHERE id = ?", id);
  }
}
