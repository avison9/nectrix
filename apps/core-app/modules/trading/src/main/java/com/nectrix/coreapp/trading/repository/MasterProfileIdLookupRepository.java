package com.nectrix.coreapp.trading.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves the calling Master's own {@code master_profile_id} for {@code
 * ProspectNominationController}'s ownership scoping, via a direct read of {@code social}'s {@code
 * master_profiles} table rather than a new {@code trading -> social} module dependency just for
 * this one lookup — same "read another module's table directly via SQL, not its Java repository
 * class" precedent {@code invitations.repository.MasterProfileLookupRepository} already
 * established for the identical need in TICKET-118.
 */
@Repository
public class MasterProfileIdLookupRepository {

  private final JdbcTemplate jdbcTemplate;

  public MasterProfileIdLookupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<UUID> findMasterProfileIdForUser(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT id FROM master_profiles WHERE user_id = ?",
            (rs, rowNum) -> rs.getString("id"),
            userId)
        .stream()
        .findFirst()
        .map(UUID::fromString);
  }
}
