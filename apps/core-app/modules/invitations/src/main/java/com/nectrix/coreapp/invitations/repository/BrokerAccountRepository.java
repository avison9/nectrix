package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. Read-only: no real
 * broker-linking (create/update) flow exists yet (Phase 1), so there's nothing to write here.
 */
@Repository
public class BrokerAccountRepository {

  private static final RowMapper<BrokerAccount> ROW_MAPPER =
      (rs, rowNum) ->
          new BrokerAccount(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("user_id")),
              rs.getString("broker_type"),
              rs.getString("broker_account_login"),
              rs.getString("display_label"),
              rs.getBoolean("is_demo"),
              rs.getString("currency"),
              rs.getString("connection_status"));

  private final JdbcTemplate jdbcTemplate;

  public BrokerAccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<BrokerAccount> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM broker_accounts WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }
}
