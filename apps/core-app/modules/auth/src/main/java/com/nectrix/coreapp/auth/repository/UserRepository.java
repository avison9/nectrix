package com.nectrix.coreapp.auth.repository;

import com.nectrix.coreapp.auth.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across the rest of core-app. */
@Repository
public class UserRepository {

  private static final RowMapper<User> ROW_MAPPER =
      (rs, rowNum) ->
          new User(
              UUID.fromString(rs.getString("id")),
              rs.getString("email"),
              rs.getString("password_hash"),
              rs.getString("display_name"),
              rs.getBoolean("two_factor_enabled"),
              rs.getString("two_factor_secret"),
              rs.getString("status"),
              uuidOrNull(rs.getString("created_by_user_id")),
              uuidOrNull(rs.getString("created_via_invitation_id")),
              uuidOrNull(rs.getString("referred_by_user_id")),
              rs.getString("region"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());

  private static UUID uuidOrNull(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private final JdbcTemplate jdbcTemplate;

  public UserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<User> findByEmail(String email) {
    return jdbcTemplate.query("SELECT * FROM users WHERE email = ?", ROW_MAPPER, email).stream()
        .findFirst();
  }

  public Optional<User> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM users WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }

  public UUID insert(
      String email,
      String passwordHash,
      String displayName,
      UUID createdByUserId,
      UUID createdViaInvitationId,
      UUID referredByUserId,
      String region) {
    String id =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO users
              (email, password_hash, display_name, created_by_user_id, created_via_invitation_id, referred_by_user_id, region)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id::text
            """,
            String.class,
            email,
            passwordHash,
            displayName,
            createdByUserId,
            createdViaInvitationId,
            referredByUserId,
            region);
    return UUID.fromString(id);
  }

  /** Stores the (already-encrypted) TOTP secret and flips the enrollment flag in one statement. */
  public void updateTwoFactor(UUID userId, boolean enabled, String encryptedSecret) {
    jdbcTemplate.update(
        "UPDATE users SET two_factor_enabled = ?, two_factor_secret = ?, updated_at = now() WHERE id = ?",
        enabled,
        encryptedSecret,
        userId);
  }

  public List<String> findRoleNames(UUID userId) {
    return jdbcTemplate.queryForList(
        """
        SELECT r.name FROM roles r
        JOIN user_roles ur ON ur.role_id = r.id
        WHERE ur.user_id = ?
        """,
        String.class,
        userId);
  }
}
