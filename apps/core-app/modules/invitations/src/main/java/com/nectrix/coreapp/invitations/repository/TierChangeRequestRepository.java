package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.TierChangeRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. TICKET-122. */
@Repository
public class TierChangeRequestRepository {

  private static final RowMapper<TierChangeRequest> ROW_MAPPER =
      (rs, rowNum) -> {
        String reviewedByUserId = rs.getString("reviewed_by_user_id");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new TierChangeRequest(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("target_role"),
            rs.getString("status"),
            rs.getString("agreement_version"),
            rs.getTimestamp("agreement_accepted_at").toInstant(),
            reviewedByUserId != null ? UUID.fromString(reviewedByUserId) : null,
            rs.getString("review_reason"),
            reviewedAt != null ? reviewedAt.toInstant() : null,
            rs.getTimestamp("created_at").toInstant());
      };

  private final JdbcTemplate jdbcTemplate;

  public TierChangeRequestRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * {@code agreementAcceptedAt} is always non-null — {@link
   * com.nectrix.coreapp.invitations.service.TierChangeRequestService#submit} refuses to insert a
   * row at all unless the caller accepted the agreement as part of this same request.
   */
  public UUID insert(
      UUID userId, String targetRole, String agreementVersion, Instant agreementAcceptedAt) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO tier_change_requests
          (user_id, target_role, agreement_version, agreement_accepted_at)
        VALUES (?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        userId,
        targetRole,
        agreementVersion,
        Timestamp.from(agreementAcceptedAt));
  }

  public Optional<TierChangeRequest> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM tier_change_requests WHERE id = ?", ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  public Optional<TierChangeRequest> findPendingByUserId(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM tier_change_requests WHERE user_id = ? AND status = 'PENDING'",
            ROW_MAPPER,
            userId)
        .stream()
        .findFirst();
  }

  /** The caller's own most recent request, regardless of status — backs {@code GET .../me}. */
  public Optional<TierChangeRequest> findLatestByUserId(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM tier_change_requests WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
            ROW_MAPPER,
            userId)
        .stream()
        .findFirst();
  }

  /** The Admin Portal's pending-queue list, oldest first (first-come-first-served review order). */
  public List<TierChangeRequest> findByStatus(String status, int page, int pageSize) {
    return jdbcTemplate.query(
        "SELECT * FROM tier_change_requests WHERE status = ? ORDER BY created_at ASC LIMIT ? OFFSET ?",
        ROW_MAPPER,
        status,
        pageSize,
        page * pageSize);
  }

  public void markApproved(UUID id, UUID reviewedByUserId, String reviewReason) {
    jdbcTemplate.update(
        """
        UPDATE tier_change_requests
        SET status = 'APPROVED', reviewed_by_user_id = ?, review_reason = ?, reviewed_at = now()
        WHERE id = ?
        """,
        reviewedByUserId,
        reviewReason,
        id);
  }

  public void markRejected(UUID id, UUID reviewedByUserId, String reviewReason) {
    jdbcTemplate.update(
        """
        UPDATE tier_change_requests
        SET status = 'REJECTED', reviewed_by_user_id = ?, review_reason = ?, reviewed_at = now()
        WHERE id = ?
        """,
        reviewedByUserId,
        reviewReason,
        id);
  }
}
