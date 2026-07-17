package com.nectrix.coreapp.billing.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
