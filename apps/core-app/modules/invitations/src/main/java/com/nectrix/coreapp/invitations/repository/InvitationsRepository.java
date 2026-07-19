package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.Invitation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. TICKET-118. */
@Repository
public class InvitationsRepository {

  private static final RowMapper<Invitation> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        String acceptedByUserId = rs.getString("accepted_by_user_id");
        String suggestedBrokerIbLinkId = rs.getString("suggested_broker_ib_link_id");
        String suggestedMoneyManagementProfileId =
            rs.getString("suggested_money_management_profile_id");
        String suggestedRiskProfileId = rs.getString("suggested_risk_profile_id");
        return new Invitation(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("master_profile_id")),
            rs.getString("invited_email"),
            rs.getString("token_hash"),
            rs.getString("status"),
            suggestedBrokerIbLinkId != null ? UUID.fromString(suggestedBrokerIbLinkId) : null,
            suggestedMoneyManagementProfileId != null
                ? UUID.fromString(suggestedMoneyManagementProfileId)
                : null,
            suggestedRiskProfileId != null ? UUID.fromString(suggestedRiskProfileId) : null,
            UUID.fromString(rs.getString("created_by_user_id")),
            rs.getTimestamp("expires_at").toInstant(),
            acceptedAt != null ? acceptedAt.toInstant() : null,
            acceptedByUserId != null ? UUID.fromString(acceptedByUserId) : null,
            rs.getTimestamp("created_at").toInstant());
      };

  private final JdbcTemplate jdbcTemplate;

  public InvitationsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(
      UUID masterProfileId,
      String invitedEmail,
      String tokenHash,
      UUID suggestedBrokerIbLinkId,
      UUID suggestedMoneyManagementProfileId,
      UUID suggestedRiskProfileId,
      UUID createdByUserId,
      Instant expiresAt) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO invitations
          (master_profile_id, invited_email, token_hash, suggested_broker_ib_link_id,
           suggested_money_management_profile_id, suggested_risk_profile_id,
           created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        invitedEmail,
        tokenHash,
        suggestedBrokerIbLinkId,
        suggestedMoneyManagementProfileId,
        suggestedRiskProfileId,
        createdByUserId,
        Timestamp.from(expiresAt));
  }

  public Optional<Invitation> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM invitations WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }

  public Optional<Invitation> findByTokenHash(String tokenHash) {
    return jdbcTemplate
        .query("SELECT * FROM invitations WHERE token_hash = ?", ROW_MAPPER, tokenHash)
        .stream()
        .findFirst();
  }

  /** {@code status} is an optional filter (null = every invitation for this Master). */
  public List<Invitation> findByMasterProfileId(UUID masterProfileId, String status) {
    if (status == null) {
      return jdbcTemplate.query(
          "SELECT * FROM invitations WHERE master_profile_id = ? ORDER BY created_at DESC",
          ROW_MAPPER,
          masterProfileId);
    }
    return jdbcTemplate.query(
        "SELECT * FROM invitations WHERE master_profile_id = ? AND status = ? ORDER BY created_at DESC",
        ROW_MAPPER,
        masterProfileId,
        status);
  }

  /**
   * Plain status transition (PENDING->EXPIRED/REVOKED) — {@link #markAccepted} covers
   * PENDING->ACCEPTED.
   */
  public void updateStatus(UUID id, String status) {
    jdbcTemplate.update("UPDATE invitations SET status = ? WHERE id = ?", status, id);
  }

  public void markAccepted(UUID id, UUID acceptedByUserId) {
    jdbcTemplate.update(
        "UPDATE invitations SET status = 'ACCEPTED', accepted_at = now(), accepted_by_user_id = ? WHERE id = ?",
        acceptedByUserId,
        id);
  }
}
