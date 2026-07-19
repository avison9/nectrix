package com.nectrix.coreapp.admin.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-117 — cross-cutting admin queries that don't belong to any single domain module (the same
 * reasoning {@code AdminController} itself already follows for its own cross-module composition).
 * Backs {@code GET /api/v1/admin/system-health}'s Postgres-derived metrics — see that ticket's plan
 * for why this is Postgres/Kafka-consumer-derived rather than a Prometheus query (no Prometheus
 * anywhere outside local dev/CI, including the one persistent {@code nectrix-dev} environment).
 */
@Repository
public class AdminRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record BrokerConnectionCount(String brokerType, String connectionStatus, long count) {}

  public List<BrokerConnectionCount> countBrokerConnectionsByTypeAndStatus() {
    return jdbcTemplate.query(
        """
        SELECT broker_type, connection_status, count(*) AS cnt
        FROM broker_accounts
        GROUP BY broker_type, connection_status
        ORDER BY broker_type, connection_status
        """,
        (rs, rowNum) ->
            new BrokerConnectionCount(
                rs.getString("broker_type"), rs.getString("connection_status"), rs.getLong("cnt")));
  }

  /**
   * Copy Engine throughput — real trades copied in the window, Postgres-derived (see class
   * Javadoc).
   */
  public long countCopiedTradesSince(Instant since) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM copied_trades WHERE created_at >= ?",
            Long.class,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  /** Copy Engine error rate's numerator — a rejected/failed trade in the same window. */
  public long countFailedCopiedTradesSince(Instant since) {
    Long count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM copied_trades
            WHERE created_at >= ? AND (status IN ('REJECTED','FAILED') OR reject_reason IS NOT NULL)
            """,
            Long.class,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  /**
   * TICKET-117 — {@code bootstrap}'s {@code ReconciliationDriftConsumer} landing spot for a real,
   * consumed {@code ReconciliationDriftDetected} event (028-reconciliation-drift-log.sql).
   */
  public void insertReconciliationDrift(
      UUID brokerAccountId, String driftType, Instant detectedAt) {
    jdbcTemplate.update(
        "INSERT INTO reconciliation_drift_log (broker_account_id, drift_type, detected_at) VALUES (?, ?, ?)",
        brokerAccountId,
        driftType,
        Timestamp.from(detectedAt));
  }

  /** System Health's reconciliation-drift-rate metric — a real count over a recent window. */
  public long countReconciliationDriftSince(Instant since) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM reconciliation_drift_log WHERE detected_at >= ?",
            Long.class,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }
}
