package com.nectrix.coreapp.bootstrap.archival;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code archival_log} (032-archival-audit-log.sql) belongs to no single module — it's a record of
 * what {@code bootstrap}'s own cross-module orchestrator did, not a domain table any one module
 * owns — so, like {@code billing}'s own precedent for tables it reads but doesn't own, this talks
 * to it via plain JdbcTemplate directly rather than inventing a module to host it in. Deliberately
 * no {@code broker_account_id} foreign key (see the migration's own comment) — it would be
 * immediately violated the moment {@link BrokerAccountArchivalOrchestrator} deletes that row.
 */
@Repository
public class ArchivalLogRepository {

  private final JdbcTemplate jdbcTemplate;

  public ArchivalLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(UUID brokerAccountId, String blobKey, String archivedRowCountsJson) {
    return jdbcTemplate.execute(
        """
        INSERT INTO archival_log (broker_account_id, blob_key, archived_row_counts)
        VALUES (?, ?, ?::jsonb)
        RETURNING id
        """,
        (PreparedStatement ps) -> {
          ps.setObject(1, brokerAccountId);
          ps.setString(2, blobKey);
          ps.setString(3, archivedRowCountsJson);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return UUID.fromString(rs.getString(1));
          }
        });
  }
}
