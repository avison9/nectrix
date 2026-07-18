package com.nectrix.coreapp.notifications.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class PushTokenRepository {

  private final JdbcTemplate jdbcTemplate;

  public PushTokenRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Idempotent — re-registering the same token is a no-op, not a duplicate row. */
  public void register(UUID userId, String token, String platform) {
    jdbcTemplate.update(
        """
        INSERT INTO push_tokens (user_id, token, platform)
        VALUES (?, ?, ?)
        ON CONFLICT (user_id, token) DO NOTHING
        """,
        userId,
        token,
        platform);
  }

  public List<String> findTokensForUser(UUID userId) {
    return jdbcTemplate.queryForList(
        "SELECT token FROM push_tokens WHERE user_id = ?", String.class, userId);
  }
}
