package com.nectrix.coreapp.notifications.repository;

import com.nectrix.coreapp.notifications.domain.NotificationPreference;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class NotificationPreferenceRepository {

  private static final RowMapper<NotificationPreference> ROW_MAPPER =
      (rs, rowNum) ->
          new NotificationPreference(
              UUID.fromString(rs.getString("user_id")),
              rs.getString("event_type"),
              rs.getString("channel"),
              rs.getBoolean("enabled"));

  private final JdbcTemplate jdbcTemplate;

  public NotificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<NotificationPreference> findAllForUser(UUID userId) {
    return jdbcTemplate.query(
        "SELECT * FROM notification_preferences WHERE user_id = ?", ROW_MAPPER, userId);
  }

  /** {@code channel} may be null to mean "every channel for this event type" (bulk lookup). */
  public List<NotificationPreference> findForUserAndEventType(UUID userId, String eventType) {
    return jdbcTemplate.query(
        "SELECT * FROM notification_preferences WHERE user_id = ? AND event_type = ?",
        ROW_MAPPER,
        userId,
        eventType);
  }

  public void upsert(UUID userId, String eventType, String channel, boolean enabled) {
    jdbcTemplate.update(
        """
        INSERT INTO notification_preferences (user_id, event_type, channel, enabled)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (user_id, event_type, channel) DO UPDATE SET enabled = EXCLUDED.enabled
        """,
        userId,
        eventType,
        channel,
        enabled);
  }
}
