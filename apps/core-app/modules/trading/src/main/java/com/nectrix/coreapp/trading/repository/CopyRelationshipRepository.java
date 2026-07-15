package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class CopyRelationshipRepository {

  private static final RowMapper<CopyRelationship> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp riskAckAt = rs.getTimestamp("risk_ack_at");
        String originatingInvitationId = rs.getString("originating_invitation_id");
        String originatingFollowRequestId = rs.getString("originating_follow_request_id");
        Timestamp stoppedAt = rs.getTimestamp("stopped_at");
        return new CopyRelationship(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("master_profile_id")),
            UUID.fromString(rs.getString("master_broker_account_id")),
            UUID.fromString(rs.getString("follower_user_id")),
            UUID.fromString(rs.getString("follower_broker_account_id")),
            UUID.fromString(rs.getString("money_management_profile_id")),
            UUID.fromString(rs.getString("risk_profile_id")),
            rs.getString("status"),
            rs.getString("copy_direction"),
            rs.getBigDecimal("performance_fee_percent"),
            rs.getString("fee_collection_method"),
            rs.getBigDecimal("high_water_mark"),
            riskAckAt != null ? riskAckAt.toInstant() : null,
            originatingInvitationId != null ? UUID.fromString(originatingInvitationId) : null,
            originatingFollowRequestId != null ? UUID.fromString(originatingFollowRequestId) : null,
            rs.getTimestamp("created_at").toInstant(),
            stoppedAt != null ? stoppedAt.toInstant() : null);
      };

  private final JdbcTemplate jdbcTemplate;

  public CopyRelationshipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<CopyRelationship> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM copy_relationships WHERE id = ?", ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  /**
   * {@code role="follower"}: rows where {@code follower_user_id = userId}. {@code role="master"}:
   * rows whose {@code master_profile_id} belongs to a {@code master_profiles} row this user owns —
   * a join, since {@code copy_relationships} has no direct {@code master_user_id} column of its
   * own. {@code status} is an optional filter (null = no filter).
   */
  public List<CopyRelationship> findAllForUser(UUID userId, String role, String status) {
    String base =
        "follower".equals(role)
            ? "SELECT cr.* FROM copy_relationships cr WHERE cr.follower_user_id = ?"
            : """
              SELECT cr.* FROM copy_relationships cr
              JOIN master_profiles mp ON mp.id = cr.master_profile_id
              WHERE mp.user_id = ?
              """;
    if (status == null) {
      return jdbcTemplate.query(base + " ORDER BY cr.created_at DESC", ROW_MAPPER, userId);
    }
    return jdbcTemplate.query(
        base + " AND cr.status = ? ORDER BY cr.created_at DESC", ROW_MAPPER, userId, status);
  }

  /** Sets {@code risk_ack_at} (idempotent — repeated calls just re-stamp "now"), always. */
  public void updateRiskAck(UUID id) {
    jdbcTemplate.update("UPDATE copy_relationships SET risk_ack_at = now() WHERE id = ?", id);
  }

  /** Plain status transition (PENDING_RISK_ACK->PENDING_AGREEMENT->ACTIVE, PAUSED<->ACTIVE). */
  public void updateStatus(UUID id, String status) {
    jdbcTemplate.update("UPDATE copy_relationships SET status = ? WHERE id = ?", status, id);
  }

  /** {@code stop} — terminal, records when. */
  public void markStopped(UUID id) {
    jdbcTemplate.update(
        "UPDATE copy_relationships SET status = 'STOPPED', stopped_at = now() WHERE id = ?", id);
  }

  /** {@code PATCH /copy-relationships/{id}} — swap in a different mm/risk profile. */
  public void updateProfiles(UUID id, UUID moneyManagementProfileId, UUID riskProfileId) {
    jdbcTemplate.update(
        """
        UPDATE copy_relationships
        SET money_management_profile_id = COALESCE(?, money_management_profile_id),
            risk_profile_id = COALESCE(?, risk_profile_id)
        WHERE id = ?
        """,
        moneyManagementProfileId,
        riskProfileId,
        id);
  }
}
