package com.nectrix.coreapp.billing.repository;

import com.nectrix.coreapp.billing.domain.CopyRelationshipBillingRef;
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
 * TICKET-113 — reads {@code copy_relationships}/{@code account_snapshots}/{@code copied_trades}
 * directly via raw SQL (see this module's build.gradle.kts comment for why that's not a
 * module-boundary violation) — the settlement engine's read side. {@code
 * PerformanceFeeLedgerRepository}/{@code HighWaterMarkRepository} own the write side.
 */
@Repository
public class SettlementDataRepository {

  private static final RowMapper<CopyRelationshipBillingRef> RELATIONSHIP_MAPPER =
      (rs, rowNum) -> {
        Timestamp riskAckAt = rs.getTimestamp("risk_ack_at");
        Timestamp stoppedAt = rs.getTimestamp("stopped_at");
        return new CopyRelationshipBillingRef(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("follower_user_id")),
            UUID.fromString(rs.getString("follower_broker_account_id")),
            rs.getBigDecimal("high_water_mark"),
            rs.getBigDecimal("performance_fee_percent"),
            rs.getString("fee_collection_method"),
            rs.getString("status"),
            riskAckAt != null ? riskAckAt.toInstant() : null,
            rs.getTimestamp("created_at").toInstant(),
            stoppedAt != null ? stoppedAt.toInstant() : null);
      };

  private final JdbcTemplate jdbcTemplate;

  public SettlementDataRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** ACTIVE/PAUSED (still accruing) plus STOPPED (may still need one final pro-rated run). */
  public List<CopyRelationshipBillingRef> findRelationshipsForSettlement() {
    return jdbcTemplate.query(
        "SELECT * FROM copy_relationships WHERE status IN ('ACTIVE','PAUSED','STOPPED')",
        RELATIONSHIP_MAPPER);
  }

  /** Latest {@code balance} sample at or before {@code at} — null if none exists yet. */
  public Optional<BigDecimal> findBalanceAtOrBefore(UUID brokerAccountId, Instant at) {
    return jdbcTemplate
        .query(
            """
            SELECT balance FROM account_snapshots
            WHERE broker_account_id = ? AND captured_at <= ?
            ORDER BY captured_at DESC LIMIT 1
            """,
            (rs, rowNum) -> rs.getBigDecimal("balance"),
            brokerAccountId,
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  /** Latest {@code equity} sample at or before {@code at} — null if none exists yet. */
  public Optional<BigDecimal> findEquityAtOrBefore(UUID brokerAccountId, Instant at) {
    return jdbcTemplate
        .query(
            """
            SELECT equity FROM account_snapshots
            WHERE broker_account_id = ? AND captured_at <= ?
            ORDER BY captured_at DESC LIMIT 1
            """,
            (rs, rowNum) -> rs.getBigDecimal("equity"),
            brokerAccountId,
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  /**
   * Sum of realized P&L for this relationship's copied_trades closed within [periodStart,
   * periodEnd).
   */
  public BigDecimal sumRealizedPnl(
      UUID copyRelationshipId, Instant periodStart, Instant periodEnd) {
    BigDecimal sum =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(realized_pnl), 0) FROM copied_trades
            WHERE copy_relationship_id = ? AND status = 'CLOSED'
              AND closed_at >= ? AND closed_at < ?
            """,
            BigDecimal.class,
            copyRelationshipId,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  public Optional<String> findStripeCustomerId(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT stripe_customer_id FROM users WHERE id = ?",
            (rs, rowNum) -> rs.getString("stripe_customer_id"),
            userId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /**
   * TICKET-114 — persists a freshly-created Stripe Customer id the first time a user checks out.
   */
  public void updateStripeCustomerId(UUID userId, String stripeCustomerId) {
    jdbcTemplate.update(
        "UPDATE users SET stripe_customer_id = ? WHERE id = ?", stripeCustomerId, userId);
  }

  /** TICKET-114 — needed to create a Stripe {@code Customer} (email is the one required field). */
  public String findEmail(UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT email FROM users WHERE id = ?", String.class, userId);
  }
}
