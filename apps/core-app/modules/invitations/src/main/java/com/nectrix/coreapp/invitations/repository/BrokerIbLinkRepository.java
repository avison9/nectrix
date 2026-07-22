package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.BrokerIbLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. TICKET-110 added the
 * read-only path; TICKET-119 adds the real write path (creation/management, Master-scoped).
 */
@Repository
public class BrokerIbLinkRepository {

  private static final RowMapper<BrokerIbLink> ROW_MAPPER =
      (rs, rowNum) ->
          new BrokerIbLink(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("master_profile_id")),
              rs.getString("broker_type"),
              rs.getString("broker_display_name"),
              rs.getString("ib_referral_url_or_code"),
              rs.getBoolean("is_active"));

  private final JdbcTemplate jdbcTemplate;

  public BrokerIbLinkRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Validates an {@code openedViaIbLinkId} at link time — must exist and still be active. */
  public boolean existsActiveById(UUID id) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM broker_ib_links WHERE id = ? AND is_active = TRUE",
            Integer.class,
            id);
    return count != null && count > 0;
  }

  /** The "open a new account via IB link" sub-flow's own data source (AC3). */
  public List<BrokerIbLink> findActiveForMaster(UUID masterProfileId) {
    return jdbcTemplate.query(
        "SELECT * FROM broker_ib_links WHERE master_profile_id = ? AND is_active = TRUE ORDER BY created_at DESC",
        ROW_MAPPER,
        masterProfileId);
  }

  /**
   * TICKET-119 — the Master's own management view: every link they've ever created, active or not
   * (a deactivated link stays visible in the Master's own list — only new invitations/account-
   * opening lose it as a selectable option, see {@link #findActiveForMaster}).
   */
  public List<BrokerIbLink> findAllForMaster(UUID masterProfileId) {
    return jdbcTemplate.query(
        "SELECT * FROM broker_ib_links WHERE master_profile_id = ? ORDER BY created_at DESC",
        ROW_MAPPER,
        masterProfileId);
  }

  public Optional<BrokerIbLink> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM broker_ib_links WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }

  public UUID insert(
      UUID masterProfileId,
      String brokerType,
      String brokerDisplayName,
      String ibReferralUrlOrCode) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO broker_ib_links (master_profile_id, broker_type, broker_display_name, ib_referral_url_or_code)
        VALUES (?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        brokerType,
        brokerDisplayName,
        ibReferralUrlOrCode);
  }

  /**
   * Deliberately never a hard delete — {@code broker_accounts.opened_via_ib_link_id} may already
   * reference this row (docs/05-domain-model.md §5.7's own historical-accuracy invariant), so
   * deactivation is the only removal path a Master ever gets.
   */
  public void deactivate(UUID id) {
    jdbcTemplate.update("UPDATE broker_ib_links SET is_active = FALSE WHERE id = ?", id);
  }
}
