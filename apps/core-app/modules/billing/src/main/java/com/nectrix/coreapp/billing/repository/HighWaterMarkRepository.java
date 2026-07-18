package com.nectrix.coreapp.billing.repository;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-113 — docs/11-fee-engine-billing.md §11.3: updates {@code copy_relationships
 * .high_water_mark} directly (raw SQL against {@code trading}'s table, see this module's
 * build.gradle.kts comment) and inserts an audit row into {@code high_water_mark_history} on every
 * change — never a silent update.
 */
@Repository
public class HighWaterMarkRepository {

  private final JdbcTemplate jdbcTemplate;

  public HighWaterMarkRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void updateHwm(UUID copyRelationshipId, BigDecimal newHwm) {
    jdbcTemplate.update(
        "UPDATE copy_relationships SET high_water_mark = ? WHERE id = ?",
        newHwm,
        copyRelationshipId);
  }

  /**
   * @param reason one of
   *     NEW_EQUITY_HIGH/RESET_ON_PAYOUT/ADMIN_ADJUSTMENT/DEPOSIT_ADJUSTMENT/WITHDRAWAL_ADJUSTMENT.
   */
  public void insertHistory(UUID copyRelationshipId, BigDecimal hwmValue, String reason) {
    jdbcTemplate.update(
        "INSERT INTO high_water_mark_history (copy_relationship_id, hwm_value, reason) VALUES (?, ?, ?)",
        copyRelationshipId,
        hwmValue,
        reason);
  }
}
