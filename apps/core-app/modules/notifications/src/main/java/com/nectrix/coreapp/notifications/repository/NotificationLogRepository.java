package com.nectrix.coreapp.notifications.repository;

import com.nectrix.coreapp.notifications.domain.NotificationLogEntry;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class NotificationLogRepository {

  private static final RowMapper<NotificationLogEntry> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationLogEntry(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("event_type"),
            rs.getString("channel"),
            rs.getString("payload"),
            sentAt != null ? sentAt.toInstant() : null,
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            readAt != null ? readAt.toInstant() : null);
      };

  private final JdbcTemplate jdbcTemplate;

  public NotificationLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(UUID userId, String eventType, String channel, String payloadJson) {
    return jdbcTemplate.execute(
        """
        INSERT INTO notification_log (user_id, event_type, channel, payload, status)
        VALUES (?, ?, ?, ?::jsonb, 'QUEUED')
        RETURNING id
        """,
        (PreparedStatement ps) -> {
          int i = 1;
          ps.setObject(i++, userId);
          ps.setString(i++, eventType);
          ps.setString(i++, channel);
          ps.setString(i, payloadJson);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return UUID.fromString(rs.getString(1));
          }
        });
  }

  public void updateStatus(UUID id, String status, Instant sentAt) {
    jdbcTemplate.update(
        "UPDATE notification_log SET status = ?, sent_at = ? WHERE id = ?",
        status,
        sentAt != null ? Timestamp.from(sentAt) : null,
        id);
  }

  /** The in-app inbox feed — {@code channel='IN_APP'} rows only, see this class's own Javadoc. */
  public List<NotificationLogEntry> findInbox(UUID userId, boolean unreadOnly) {
    String sql =
        "SELECT * FROM notification_log WHERE user_id = ? AND channel = 'IN_APP'"
            + (unreadOnly ? " AND read_at IS NULL" : "")
            + " ORDER BY created_at DESC";
    return jdbcTemplate.query(sql, ROW_MAPPER, userId);
  }

  public Optional<NotificationLogEntry> findByIdForUser(UUID id, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM notification_log WHERE id = ? AND user_id = ?", ROW_MAPPER, id, userId)
        .stream()
        .findFirst();
  }

  /** Idempotent — re-marking an already-read row is a harmless no-op re-stamp. */
  public void markRead(UUID id) {
    jdbcTemplate.update("UPDATE notification_log SET read_at = now() WHERE id = ?", id);
  }
}
