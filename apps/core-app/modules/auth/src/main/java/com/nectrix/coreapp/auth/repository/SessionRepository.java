package com.nectrix.coreapp.auth.repository;

import com.nectrix.coreapp.auth.domain.Session;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

  private static final RowMapper<Session> ROW_MAPPER =
      (rs, rowNum) ->
          new Session(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("user_id")),
              rs.getString("refresh_token_hash"),
              rs.getString("device_info"),
              rs.getString("ip_address"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("expires_at").toInstant(),
              rs.getTimestamp("revoked_at") == null
                  ? null
                  : rs.getTimestamp("revoked_at").toInstant(),
              rs.getString("revoked_reason"));

  private final JdbcTemplate jdbcTemplate;

  public SessionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID create(
      UUID userId,
      String refreshTokenHash,
      String deviceInfoJson,
      String ipAddress,
      Instant expiresAt) {
    String id =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO sessions (user_id, refresh_token_hash, device_info, ip_address, expires_at)
            VALUES (?, ?, ?::jsonb, ?::inet, ?)
            RETURNING id::text
            """,
            String.class,
            userId,
            refreshTokenHash,
            deviceInfoJson,
            ipAddress,
            Timestamp.from(expiresAt));
    return UUID.fromString(id);
  }

  /**
   * Atomic claim-and-rotate — the race-safety primitive the whole rotation design depends on.
   * Postgres's row-level lock on this UPDATE makes a concurrent second caller presenting the same
   * token block, then fail the WHERE clause once the first commits: exactly one caller ever "wins"
   * a given token, forking two live sessions from one rotation is impossible.
   *
   * <p>Returns the session's user_id if the token was valid, unrotated, and unexpired — empty
   * otherwise (caller must then run {@link #findByRefreshTokenHash} to disambiguate *why*, since
   * "expired" and "already rotated/revoked" require different responses — see AuthService).
   */
  public Optional<UUID> claimForRotation(String refreshTokenHash) {
    return jdbcTemplate
        .query(
            """
            UPDATE sessions
            SET revoked_at = now(), revoked_reason = 'ROTATED'
            WHERE refresh_token_hash = ? AND revoked_at IS NULL AND expires_at > now()
            RETURNING user_id::text
            """,
            (rs, rowNum) -> UUID.fromString(rs.getString(1)),
            refreshTokenHash)
        .stream()
        .findFirst();
  }

  public Optional<Session> findByRefreshTokenHash(String refreshTokenHash) {
    return jdbcTemplate
        .query("SELECT * FROM sessions WHERE refresh_token_hash = ?", ROW_MAPPER, refreshTokenHash)
        .stream()
        .findFirst();
  }

  /** Reuse-detection response: nukes every other still-active session for this user. */
  public void revokeAllForUser(UUID userId, String reason) {
    jdbcTemplate.update(
        "UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE user_id = ? AND revoked_at IS NULL",
        reason,
        userId);
  }

  /** Single-device logout — never mass revocation. */
  public void revoke(UUID sessionId, String reason) {
    jdbcTemplate.update(
        "UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ?",
        reason,
        sessionId);
  }
}
