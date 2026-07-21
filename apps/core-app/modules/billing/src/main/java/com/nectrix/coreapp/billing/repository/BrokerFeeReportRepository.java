package com.nectrix.coreapp.billing.repository;

import com.nectrix.coreapp.billing.domain.BrokerFeeReport;
import com.nectrix.coreapp.billing.domain.BrokerFeeReportLine;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. TICKET-120 — {@code
 * broker_fee_reports}/{@code broker_fee_report_lines} are billing's own tables, but the bundling
 * query reads {@code copy_relationships}/{@code broker_accounts} directly (same "read another
 * module's table via raw SQL, not its Java repository class" precedent {@code
 * SettlementDataRepository} already established for TICKET-113).
 */
@Repository
public class BrokerFeeReportRepository {

  private static final RowMapper<BrokerFeeReport> REPORT_ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp confirmedDeductedAt = rs.getTimestamp("confirmed_deducted_at");
        Timestamp confirmedPaidAt = rs.getTimestamp("confirmed_paid_at");
        return new BrokerFeeReport(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("master_profile_id")),
            rs.getString("broker_type"),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            rs.getString("status"),
            rs.getString("report_object_key"),
            sentAt != null ? sentAt.toInstant() : null,
            confirmedDeductedAt != null ? confirmedDeductedAt.toInstant() : null,
            confirmedPaidAt != null ? confirmedPaidAt.toInstant() : null,
            UUID.fromString(rs.getString("generated_by_user_id")),
            rs.getTimestamp("created_at").toInstant());
      };

  private final JdbcTemplate jdbcTemplate;

  public BrokerFeeReportRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Resolves the calling Master's own {@code master_profile_id}, via the same direct read of {@code
   * social}'s {@code master_profiles} table {@link #findBundleCandidates}/{@code
   * PerformanceFeeLedgerRepository#findAllForUser} already establish — no new module dependency.
   */
  public Optional<UUID> findMasterProfileIdForUser(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT id FROM master_profiles WHERE user_id = ?",
            (rs, rowNum) -> rs.getString("id"),
            userId)
        .stream()
        .findFirst()
        .map(UUID::fromString);
  }

  /**
   * A candidate {@code performance_fee_ledger} row for bundling, joined out to the follower's real
   * broker login/currency (what {@code broker_fee_report_lines} itself stores, docs/11-fee-engine-
   * billing.md §11.5's own report-line shape).
   */
  public record BundleCandidate(
      UUID performanceFeeLedgerId,
      BigDecimal feeAmount,
      String followerBrokerAccountLogin,
      String currency) {}

  /**
   * AC3 — exactly the {@code PENDING} ledger rows for this Master's {@code BROKER_PARTNERSHIP}
   * relationships with this broker, excluding (defensively — not itself a stated AC, but a real
   * double-billing risk otherwise) any ledger row already bundled into a PRIOR report of any
   * status, so the same fee is never reported to the broker twice even if a Master generates two
   * overlapping draft reports before sending either.
   */
  public List<BundleCandidate> findBundleCandidates(UUID masterProfileId, String brokerType) {
    return jdbcTemplate.query(
        """
        SELECT pfl.id AS ledger_id, pfl.master_fee_amount, ba.broker_account_login, ba.currency
        FROM performance_fee_ledger pfl
        JOIN copy_relationships cr ON cr.id = pfl.copy_relationship_id
        JOIN broker_accounts mba ON mba.id = cr.master_broker_account_id
        JOIN broker_accounts ba ON ba.id = cr.follower_broker_account_id
        WHERE cr.master_profile_id = ?
          AND cr.fee_collection_method = 'BROKER_PARTNERSHIP'
          AND mba.broker_type = ?
          AND pfl.status = 'PENDING'
          AND NOT EXISTS (
            SELECT 1 FROM broker_fee_report_lines l WHERE l.performance_fee_ledger_id = pfl.id
          )
        ORDER BY pfl.period_end
        """,
        (rs, rowNum) ->
            new BundleCandidate(
                UUID.fromString(rs.getString("ledger_id")),
                rs.getBigDecimal("master_fee_amount"),
                rs.getString("broker_account_login"),
                rs.getString("currency")),
        masterProfileId,
        brokerType);
  }

  /**
   * @return empty if a report for this exact (master, broker, period) already exists — the table's
   *     own {@code UNIQUE (master_profile_id, broker_type, period_start, period_end)} constraint
   *     catching a genuine duplicate-generation attempt, same idempotent-no-op pattern {@code
   *     PerformanceFeeLedgerRepository#tryInsert} already established.
   */
  public Optional<UUID> tryInsertReport(
      UUID masterProfileId,
      String brokerType,
      Instant periodStart,
      Instant periodEnd,
      String reportObjectKey,
      UUID generatedByUserId) {
    try {
      UUID id =
          jdbcTemplate.queryForObject(
              """
              INSERT INTO broker_fee_reports
                (master_profile_id, broker_type, period_start, period_end, report_object_key, generated_by_user_id)
              VALUES (?, ?, ?, ?, ?, ?)
              RETURNING id
              """,
              UUID.class,
              masterProfileId,
              brokerType,
              Timestamp.from(periodStart),
              Timestamp.from(periodEnd),
              reportObjectKey,
              generatedByUserId);
      return Optional.ofNullable(id);
    } catch (org.springframework.dao.DataIntegrityViolationException alreadyGenerated) {
      return Optional.empty();
    }
  }

  public void insertLine(UUID brokerFeeReportId, BundleCandidate candidate) {
    jdbcTemplate.update(
        """
        INSERT INTO broker_fee_report_lines
          (broker_fee_report_id, performance_fee_ledger_id, follower_broker_account_login, fee_amount, currency)
        VALUES (?, ?, ?, ?, ?)
        """,
        brokerFeeReportId,
        candidate.performanceFeeLedgerId(),
        candidate.followerBrokerAccountLogin(),
        candidate.feeAmount(),
        candidate.currency());
  }

  public Optional<BrokerFeeReport> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM broker_fee_reports WHERE id = ?", REPORT_ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  public List<BrokerFeeReport> findAllForMaster(UUID masterProfileId) {
    return jdbcTemplate.query(
        "SELECT * FROM broker_fee_reports WHERE master_profile_id = ? ORDER BY created_at DESC",
        REPORT_ROW_MAPPER,
        masterProfileId);
  }

  public List<BrokerFeeReportLine> findLinesForReport(UUID reportId) {
    return jdbcTemplate.query(
        "SELECT * FROM broker_fee_report_lines WHERE broker_fee_report_id = ? ORDER BY id",
        (rs, rowNum) ->
            new BrokerFeeReportLine(
                rs.getLong("id"),
                UUID.fromString(rs.getString("broker_fee_report_id")),
                UUID.fromString(rs.getString("performance_fee_ledger_id")),
                rs.getString("follower_broker_account_login"),
                rs.getBigDecimal("fee_amount"),
                rs.getString("currency")),
        reportId);
  }

  /**
   * AC5 — each of the 3 report-status transitions (send/confirm-deducted/confirm-paid) is one call
   * here, cascading BOTH this report's own status/timestamp column AND every bundled {@code
   * performance_fee_ledger} row's status in the same statement pair. {@code timestampColumn} is one
   * of the 3 real column names ({@code sent_at}/{@code confirmed_deducted_at}/{@code
   * confirmed_paid_at}) — never user input, always a fixed literal from {@code
   * BrokerFeeReportService}'s own 3 call sites.
   */
  public void transitionStatus(
      UUID reportId, String newStatus, String timestampColumn, String ledgerStatus) {
    jdbcTemplate.update(
        "UPDATE broker_fee_reports SET status = ?, " + timestampColumn + " = now() WHERE id = ?",
        newStatus,
        reportId);
    jdbcTemplate.update(
        """
        UPDATE performance_fee_ledger SET status = ?
        WHERE id IN (SELECT performance_fee_ledger_id FROM broker_fee_report_lines WHERE broker_fee_report_id = ?)
        """,
        ledgerStatus,
        reportId);
  }
}
