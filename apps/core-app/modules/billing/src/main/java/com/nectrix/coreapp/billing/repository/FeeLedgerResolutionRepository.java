package com.nectrix.coreapp.billing.repository;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-117 — {@code fee_ledger_resolutions} (027-fee-ledger-resolutions.sql), the
 * compensating-record pattern {@code high_water_mark_history} already establishes: append-only, one
 * row per resolve action, the original {@code performance_fee_ledger} row's {@code
 * computation_detail}/amounts are never touched — only its {@code status} transitions (via {@link
 * PerformanceFeeLedgerRepository#updateStatus}), called alongside this insert.
 */
@Repository
public class FeeLedgerResolutionRepository {

  private final JdbcTemplate jdbcTemplate;

  public FeeLedgerResolutionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(
      UUID ledgerId,
      String resolution,
      String note,
      BigDecimal adjustedAmount,
      UUID resolvedByUserId) {
    String id =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO fee_ledger_resolutions
              (ledger_id, resolution, note, adjusted_amount, resolved_by_user_id)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id::text
            """,
            String.class,
            ledgerId,
            resolution,
            note,
            adjustedAmount,
            resolvedByUserId);
    return UUID.fromString(id);
  }
}
