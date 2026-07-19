package com.nectrix.coreapp.invitations.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-118 — resolves the calling Master's own {@code master_profile_id} for {@code
 * POST /master/invitations}'s ownership scoping, via a direct read of {@code social}'s {@code
 * master_profiles} table rather than a new {@code invitations -> social} module dependency: {@code
 * social} already depends one-way on {@code invitations} (for {@code BrokerAccountLookupApi}), so
 * the reverse edge would create a cycle. Same "read another module's table directly via SQL, not
 * its Java repository class" precedent {@code modules:notifications}' {@code
 * NotificationTargetLookupRepository} and {@code modules:billing}'s {@code SettlementDataRepository}
 * already established for this exact kind of one-off cross-module read.
 */
@Repository
public class MasterProfileLookupRepository {

  private final JdbcTemplate jdbcTemplate;

  public MasterProfileLookupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<UUID> findMasterProfileIdForUser(UUID userId) {
    return jdbcTemplate
        .query("SELECT id FROM master_profiles WHERE user_id = ?", (rs, rowNum) -> rs.getString("id"), userId)
        .stream()
        .findFirst()
        .map(UUID::fromString);
  }

  /** TICKET-118 — the by-token accept-screen preview's own "who invited you?" display name. */
  public Optional<String> findDisplayName(UUID masterProfileId) {
    return jdbcTemplate
        .query(
            "SELECT display_name FROM master_profiles WHERE id = ?",
            (rs, rowNum) -> rs.getString("display_name"),
            masterProfileId)
        .stream()
        .findFirst();
  }
}
