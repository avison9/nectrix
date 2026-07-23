package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import java.sql.Timestamp;
import java.time.Instant;
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
 * constraint does NOT reliably prevent linking the same cTrader account twice: cTrader accounts
 * have no real "server" concept, so {@code server_name} stays NULL for every cTrader row, and
 * Postgres treats each NULL as distinct for uniqueness purposes, so two NULL-server_name rows with
 * an otherwise-identical key do not collide (MT4/MT5 rows, which DO set a real {@code server_name}
 * as of TICKET-101/102's own follow-up, are covered by the constraint as intended). {@code
 * existsForUser} is the application-level guard that covers the cTrader gap the constraint can't.
 */
@Repository
public class BrokerAccountRepository {

  private static final RowMapper<BrokerAccount> ROW_MAPPER =
      (rs, rowNum) -> {
        String openedViaIbLinkId = rs.getString("opened_via_ib_link_id");
        Timestamp lastHealthCheckAt = rs.getTimestamp("last_health_check_at");
        return new BrokerAccount(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("broker_type"),
            rs.getString("broker_account_login"),
            rs.getString("display_label"),
            rs.getBoolean("is_demo"),
            rs.getString("currency"),
            rs.getString("connection_role"),
            openedViaIbLinkId != null ? UUID.fromString(openedViaIbLinkId) : null,
            rs.getString("connection_status"),
            lastHealthCheckAt != null ? lastHealthCheckAt.toInstant() : null,
            rs.getString("broker_name"),
            rs.getString("server_name"));
      };

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
   *
   * <p>Convenience delegate kept for callers that don't yet have a connection_role/IB-link decision
   * to make — defaults to {@code FOLLOWER_ONLY} (docs/07 §7.5's own default for invite-created
   * accounts) and no IB link.
   */
  public UUID insert(
      UUID userId,
      String brokerType,
      String brokerAccountLogin,
      String displayLabel,
      boolean isDemo,
      byte[] credentialsCiphertext,
      short credentialsKeyVersion) {
    return insert(
        userId,
        brokerType,
        brokerAccountLogin,
        displayLabel,
        isDemo,
        credentialsCiphertext,
        credentialsKeyVersion,
        "FOLLOWER_ONLY",
        null,
        null,
        null);
  }

  /**
   * TICKET-110 — threads connection_role/opened_via_ib_link_id through at link time. TICKET-101/102
   * follow-up — {@code brokerName}/{@code serverName} are both nullable: cTrader linking has a real
   * broker name (from cTrader's own account list response) but no "server" concept; MT4/MT5 linking
   * has a real server but no broker-name source beyond a user-entered value.
   */
  public UUID insert(
      UUID userId,
      String brokerType,
      String brokerAccountLogin,
      String displayLabel,
      boolean isDemo,
      byte[] credentialsCiphertext,
      short credentialsKeyVersion,
      String connectionRole,
      UUID openedViaIbLinkId,
      String brokerName,
      String serverName) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO broker_accounts
          (user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_role, opened_via_ib_link_id,
           broker_name, server_name)
        VALUES (?, ?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        userId,
        brokerType,
        brokerAccountLogin,
        displayLabel,
        isDemo,
        credentialsCiphertext,
        credentialsKeyVersion,
        connectionRole,
        openedViaIbLinkId,
        brokerName,
        serverName);
  }

  /**
   * TICKET-114 — how many of the caller's own accounts already carry a given {@code
   * connection_role}, for master-slot/follower-slot capability enforcement. {@code "BOTH"} counts
   * toward both a master-slot and a follower-slot check (a single account acting as both) — callers
   * pass {@code "MASTER_ONLY"} or {@code "FOLLOWER_ONLY"} and this matches accounts set to that
   * role OR {@code "BOTH"}.
   */
  public int countForUserByConnectionRole(UUID userId, String connectionRole) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM broker_accounts WHERE user_id = ? AND connection_role IN (?, 'BOTH')",
            Integer.class,
            userId,
            connectionRole);
    return count == null ? 0 : count;
  }

  /** TICKET-110 — list endpoint's own query, scoped to the caller (never a bare findAll). */
  public List<BrokerAccount> findAllForUser(UUID userId) {
    return jdbcTemplate.query(
        "SELECT * FROM broker_accounts WHERE user_id = ? ORDER BY created_at DESC",
        ROW_MAPPER,
        userId);
  }

  /**
   * TICKET-110 — PATCH's write path; both params are the already-resolved (existing-if-absent)
   * values.
   */
  public void updateDisplayLabelAndRole(UUID id, String displayLabel, String connectionRole) {
    jdbcTemplate.update(
        "UPDATE broker_accounts SET display_label = ?, connection_role = ?, updated_at = now() WHERE id = ?",
        displayLabel,
        connectionRole,
        id);
  }

  /**
   * TICKET-110 — DELETE's write path. broker_accounts has no ON DELETE CASCADE from
   * copy_relationships (unlike account_snapshots/symbol_mappings) — a still-referenced row throws
   * DataIntegrityViolationException, translated to a 409 by the caller, never silently orphaning a
   * future relationship row.
   */
  public void delete(UUID id) {
    jdbcTemplate.update("DELETE FROM broker_accounts WHERE id = ?", id);
  }

  /**
   * TICKET-101 follow-up — {@code follow_requests.follower_broker_account_id} (003-invitations-
   * onboarding.sql) has no {@code ON DELETE CASCADE} and, unlike {@code copy_relationships}, no
   * repository/module anywhere ever clears it — a real gap the archival flow's own {@code
   * hardDelete} found the hard way (a genuinely DISCONNECTED, otherwise-unreferenced account still
   * 409'd). Deliberately scoped to the archival flow only, not plain {@link #delete} — a follow
   * request carries no trade/commission data worth a durable archive (its one meaningful outcome,
   * if approved, already lives in {@code copy_relationships}, which the archival flow separately
   * archives), so this is a safe pre-delete cleanup, not something {@link #delete} itself should
   * silently do (that would defeat the "409 signals real unarchived history" contract other
   * referencing tables rely on).
   */
  public void deleteFollowRequestsForBrokerAccount(UUID brokerAccountId) {
    jdbcTemplate.update(
        "DELETE FROM follow_requests WHERE follower_broker_account_id = ?", brokerAccountId);
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

  /** Row shape for {@link #findSnapshotCandidates}. */
  public record SnapshotCandidate(UUID id, String brokerType) {}

  /**
   * Bugfix — every broker account {@code AccountSnapshotSchedulerJob} should periodically snapshot
   * into {@code account_snapshots}, so analytics' own equity curve stops being sparse/stale (it was
   * previously only ever written as a side effect of copy-engine dispatching a trade). Three cases,
   * not just "has a copy_relationships row": a Master's own {@code primary_broker_account_id}
   * (needed even before they have a single Follower yet), plus either side of an ACTIVE/PAUSED
   * {@code copy_relationships} row. Scoped to {@code connection_status = 'CONNECTED'} — a
   * non-connected account has no live handle on the broker-adapters side, so attempting it would
   * just fail every cycle for no benefit.
   */
  public List<SnapshotCandidate> findSnapshotCandidates() {
    return jdbcTemplate.query(
        """
        SELECT DISTINCT ba.id, ba.broker_type
        FROM broker_accounts ba
        WHERE ba.connection_status = 'CONNECTED'
          AND ba.id IN (
            SELECT primary_broker_account_id FROM master_profiles
            UNION
            SELECT master_broker_account_id FROM copy_relationships WHERE status IN ('ACTIVE','PAUSED')
            UNION
            SELECT follower_broker_account_id FROM copy_relationships WHERE status IN ('ACTIVE','PAUSED')
          )
        """,
        (rs, rowNum) ->
            new SnapshotCandidate(UUID.fromString(rs.getString("id")), rs.getString("broker_type")));
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

  /**
   * TICKET-101 follow-up — the scheduled archival sweep's own candidate query: every account that
   * has sat {@code DISCONNECTED} since before {@code olderThan} ({@code updated_at} is re-stamped
   * by {@link #updateConnectionStatus} every time the status changes, so this is genuinely "time
   * since disconnect," not just "time since last edit").
   */
  public List<UUID> findStaleDisconnectedIds(Instant olderThan) {
    return jdbcTemplate.query(
        "SELECT id FROM broker_accounts WHERE connection_status = 'DISCONNECTED' AND updated_at < ?",
        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
        Timestamp.from(olderThan));
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
