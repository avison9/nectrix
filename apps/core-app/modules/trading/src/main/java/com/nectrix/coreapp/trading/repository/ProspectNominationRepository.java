package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.ProspectNomination;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class ProspectNominationRepository {

  private static final RowMapper<ProspectNomination> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        String invitationId = rs.getString("invitation_id");
        return new ProspectNomination(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("master_profile_id")),
            UUID.fromString(rs.getString("nominated_by_user_id")),
            rs.getString("prospect_email"),
            rs.getString("status"),
            invitationId != null ? UUID.fromString(invitationId) : null,
            rs.getTimestamp("created_at").toInstant(),
            decidedAt != null ? decidedAt.toInstant() : null);
      };

  private final JdbcTemplate jdbcTemplate;

  public ProspectNominationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(UUID masterProfileId, UUID nominatedByUserId, String prospectEmail) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO prospect_nominations (master_profile_id, nominated_by_user_id, prospect_email)
        VALUES (?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        nominatedByUserId,
        prospectEmail);
  }

  public Optional<ProspectNomination> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM prospect_nominations WHERE id = ?", ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  /** {@code status} is an optional filter (null = every nomination for this Master). */
  public List<ProspectNomination> findByMasterProfileId(UUID masterProfileId, String status) {
    if (status == null) {
      return jdbcTemplate.query(
          "SELECT * FROM prospect_nominations WHERE master_profile_id = ? ORDER BY created_at DESC",
          ROW_MAPPER,
          masterProfileId);
    }
    return jdbcTemplate.query(
        "SELECT * FROM prospect_nominations WHERE master_profile_id = ? AND status = ? ORDER BY created_at DESC",
        ROW_MAPPER,
        masterProfileId,
        status);
  }

  /** The Follower's own "my referral history" view. */
  public List<ProspectNomination> findByNominatedByUserId(UUID nominatedByUserId) {
    return jdbcTemplate.query(
        "SELECT * FROM prospect_nominations WHERE nominated_by_user_id = ? ORDER BY created_at DESC",
        ROW_MAPPER,
        nominatedByUserId);
  }

  public void markInvited(UUID id, UUID invitationId) {
    jdbcTemplate.update(
        "UPDATE prospect_nominations SET status = 'INVITED', invitation_id = ?, decided_at = now() WHERE id = ?",
        invitationId,
        id);
  }

  public void markDismissed(UUID id) {
    jdbcTemplate.update(
        "UPDATE prospect_nominations SET status = 'DISMISSED', decided_at = now() WHERE id = ?",
        id);
  }

  /**
   * {@code /inbox}'s own "who referred this prospect?" display — a direct read of {@code auth}'s
   * {@code users} table rather than a new {@code trading -> auth} module dependency just for one
   * column, same "read another module's table directly via SQL, not its Java repository class"
   * precedent {@code modules:notifications}' {@code NotificationTargetLookupRepository} and {@code
   * invitations.repository.MasterProfileLookupRepository} already established.
   */
  public Map<UUID, String> findDisplayNamesByIds(Set<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = List.copyOf(userIds);
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    List<Object[]> rows =
        jdbcTemplate.query(
            "SELECT id, display_name FROM users WHERE id IN (" + placeholders + ")",
            (rs, rowNum) -> new Object[] {rs.getString("id"), rs.getString("display_name")},
            ids.toArray());
    return rows.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                row -> UUID.fromString((String) row[0]), row -> (String) row[1]));
  }
}
