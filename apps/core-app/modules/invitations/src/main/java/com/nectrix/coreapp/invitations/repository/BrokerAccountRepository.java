package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app.
 *
 * <p>TICKET-101 — {@code insert}/{@code existsForUser} add the real broker-linking write path.
 * {@code broker_accounts}' own {@code UNIQUE (broker_type, broker_account_login, server_name)}
 * constraint does NOT reliably prevent linking the same cTrader account twice: this table never
 * sets {@code server_name}, and Postgres treats each NULL as distinct for uniqueness purposes, so
 * two NULL-server_name rows with an otherwise-identical key do not collide. {@code existsForUser}
 * is the application-level guard that constraint can't provide.
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

  public boolean existsForUser(UUID userId, String brokerType, String brokerAccountLogin) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM broker_accounts WHERE user_id = ? AND broker_type = ? AND broker_account_login = ?",
            Integer.class,
            userId,
            brokerType,
            brokerAccountLogin);
    return count != null && count > 0;
  }

  /**
   * currency is a placeholder ("USD") at link time — the real account currency isn't known until
   * apps/broker-adapters reports the first real GetAccountSnapshot; correcting it is a documented
   * follow-up (see this ticket's live-verification runbook), not silently wrong data left in place
   * forever.
   */
  public UUID insert(
      UUID userId,
      String brokerType,
      String brokerAccountLogin,
      String displayLabel,
      boolean isDemo,
      byte[] credentialsCiphertext,
      short credentialsKeyVersion) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO broker_accounts
          (user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version)
        VALUES (?, ?, ?, ?, ?, 'USD', ?, ?)
        RETURNING id
        """,
        UUID.class,
        userId,
        brokerType,
        brokerAccountLogin,
        displayLabel,
        isDemo,
        credentialsCiphertext,
        credentialsKeyVersion);
  }

  /** Row shape for the internal listing endpoint (task #119) — deliberately no credentials. */
  public record AccountRef(UUID id, String connectionStatus) {}

  /**
   * A plain {@code IN (?, ?, ...)} clause built to match statuses.size() — plain JdbcTemplate (no
   * NamedParameterJdbcTemplate anywhere in this codebase) doesn't support binding a Java List/array
   * directly to a Postgres {@code ANY(?)} array parameter without an explicit {@code
   * java.sql.Array}, so this is the simpler, convention-consistent option.
   */
  public List<AccountRef> findByStatusAndBrokerType(List<String> statuses, String brokerType) {
    String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
    Object[] args = new Object[statuses.size() + 1];
    args[0] = brokerType;
    for (int i = 0; i < statuses.size(); i++) {
      args[i + 1] = statuses.get(i);
    }
    return jdbcTemplate.query(
        "SELECT id, connection_status FROM broker_accounts WHERE broker_type = ? AND connection_status IN ("
            + placeholders
            + ")",
        (rs, rowNum) ->
            new AccountRef(UUID.fromString(rs.getString("id")), rs.getString("connection_status")),
        args);
  }

  /**
   * Row shape for the internal credentials endpoint (task #119) — the ciphertext, not the secret.
   */
  public record CredentialsRow(
      byte[] credentialsCiphertext,
      short credentialsKeyVersion,
      String brokerAccountLogin,
      boolean isDemo) {}

  public Optional<CredentialsRow> findCredentialsById(UUID id) {
    return jdbcTemplate
        .query(
            "SELECT credentials_ciphertext, credentials_key_version, broker_account_login, is_demo FROM broker_accounts WHERE id = ?",
            (rs, rowNum) ->
                new CredentialsRow(
                    rs.getBytes("credentials_ciphertext"),
                    rs.getShort("credentials_key_version"),
                    rs.getString("broker_account_login"),
                    rs.getBoolean("is_demo")),
            id)
        .stream()
        .findFirst();
  }

  public void updateConnectionStatus(UUID id, String status) {
    jdbcTemplate.update(
        "UPDATE broker_accounts SET connection_status = ?, last_health_check_at = now(), updated_at = now() WHERE id = ?",
        status,
        id);
  }

  /** TICKET-101 task #120 — re-encrypted tokens after a successful token-refresh job cycle. */
  public void updateCredentials(
      UUID id, byte[] credentialsCiphertext, short credentialsKeyVersion) {
    jdbcTemplate.update(
        "UPDATE broker_accounts SET credentials_ciphertext = ?, credentials_key_version = ?, updated_at = now() WHERE id = ?",
        credentialsCiphertext,
        credentialsKeyVersion,
        id);
  }
}
