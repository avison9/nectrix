package com.nectrix.coreapp.trading.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-118 — {@code GET /users/me/pending-invitation}'s own "was this account created via an
 * invitation?" check, via a direct read of {@code auth}'s {@code users} table rather than a new
 * {@code trading -> auth} module dependency just for this one column — same "read another module's
 * table directly via SQL, not its Java repository class" precedent {@code modules:notifications}'
 * {@code NotificationTargetLookupRepository} already established (see {@code
 * invitations.repository.MasterProfileLookupRepository}'s identical reasoning). Only covers the
 * invitation that created the caller's very first account — a pre-existing user who later accepts a
 * second Master's invite carries that invitation id through the frontend flow instead (see {@code
 * InvitationCopySetupController}'s own Javadoc).
 */
@Repository
public class UserInvitationLookupRepository {

  private final JdbcTemplate jdbcTemplate;

  public UserInvitationLookupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<UUID> findCreatedViaInvitationId(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT created_via_invitation_id FROM users WHERE id = ?",
            (rs, rowNum) -> rs.getString("created_via_invitation_id"),
            userId)
        .stream()
        .findFirst()
        .filter(java.util.Objects::nonNull)
        .map(UUID::fromString);
  }
}
