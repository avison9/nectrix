package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.BrokerIbLink;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. TICKET-110 — read-only:
 * writes to `broker_ib_links` (creation/management) are TICKET-119's own scope, not this one's.
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
}
