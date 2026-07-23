package com.nectrix.coreapp.invitations.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bugfix — the one-table repository for {@code AccountSnapshotSchedulerJob}'s own write path.
 * {@code account_snapshots} (004-broker-connectivity.sql) has no unique constraint on {@code
 * (broker_account_id, day)}, so this is purely additive alongside apps/copy-engine's own existing
 * best-effort write on trade dispatch — extra same-day rows are harmless, {@code
 * LeaderboardComputationRepository#findDailyEquityCurve}'s own {@code DISTINCT ON (day) ... ORDER
 * BY captured_at DESC} already picks whichever is most recent.
 */
@Repository
public class AccountSnapshotRepository {

  private final JdbcTemplate jdbcTemplate;

  public AccountSnapshotRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insertSnapshot(
      UUID brokerAccountId,
      double balance,
      double equity,
      double usedMargin,
      double freeMargin,
      Double marginLevelPct) {
    jdbcTemplate.update(
        """
        INSERT INTO account_snapshots
          (broker_account_id, balance, equity, used_margin, free_margin, margin_level_pct)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        brokerAccountId,
        balance,
        equity,
        usedMargin,
        freeMargin,
        marginLevelPct);
  }
}
