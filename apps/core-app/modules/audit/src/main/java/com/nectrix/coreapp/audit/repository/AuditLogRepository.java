package com.nectrix.coreapp.audit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes to, and (TICKET-012) reads from, `audit_log` (docs/17-security-architecture.md §17.6 —
 * write-restricted at the app-DB-role level: INSERT+SELECT only, no UPDATE/DELETE, so even a
 * compromised app credential can't rewrite history — see 013-app-role-grants.sql).
 *
 * <p>Extracted out of {@code modules:admin} into this shared-kernel module (same tier as {@code
 * modules:crypto} — no domain data/business capability of its own, just a reusable infrastructure
 * primitive) once a second bounded-context module needed to write {@code audit_log} too, exactly as
 * this class's own original Javadoc anticipated: the Nectrix-hosted MT5/ MT4 terminal-provisioning
 * flow needs to audit every real plaintext-password fetch, which is at least as sensitive as
 * anything the Admin Portal already audits.
 */
@Repository
public class AuditLogRepository {

  private final JdbcTemplate jdbcTemplate;

  public AuditLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * @param actorUserId nullable — SYSTEM-initiated actions have no user actor.
   * @param actorType one of USER/ADMIN/SYSTEM (see `audit_log`'s check constraint).
   * @param targetType/targetId nullable — free-text, not a real FK (the target can be any table).
   * @param metadataJson nullable — a pre-serialized JSON object string, cast to `jsonb`.
   */
  public void insert(
      UUID actorUserId,
      String actorType,
      String action,
      String targetType,
      String targetId,
      String metadataJson) {
    jdbcTemplate.update(
        """
        INSERT INTO audit_log (actor_user_id, actor_type, action, target_type, target_id, metadata)
        VALUES (?, ?, ?, ?, ?, ?::jsonb)
        """,
        actorUserId,
        actorType,
        action,
        targetType,
        targetId,
        metadataJson);
  }

  public record AuditLogEntry(
      long id,
      UUID actorUserId,
      String actorType,
      String action,
      String targetType,
      String targetId,
      String metadataJson,
      Instant createdAt) {}

  /** All filter params nullable — an absent one is simply not added to the WHERE clause. */
  public record Filter(
      UUID actorUserId, String targetType, String targetId, Instant from, Instant to) {}

  /** Newest-first, matching the Audit Log viewer's default sort. {@code page} is 0-indexed. */
  public List<AuditLogEntry> findPage(Filter filter, int page, int pageSize) {
    List<Object> params = new ArrayList<>();
    String where = buildWhereClause(filter, params);
    params.add(pageSize);
    params.add(page * pageSize);
    return jdbcTemplate.query(
        """
        SELECT id, actor_user_id, actor_type, action, target_type, target_id, metadata::text AS metadata, created_at
        FROM audit_log
        """
            + where
            + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
        (rs, rowNum) ->
            new AuditLogEntry(
                rs.getLong("id"),
                rs.getString("actor_user_id") == null
                    ? null
                    : UUID.fromString(rs.getString("actor_user_id")),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("metadata"),
                rs.getTimestamp("created_at").toInstant()),
        params.toArray());
  }

  public long count(Filter filter) {
    List<Object> params = new ArrayList<>();
    String where = buildWhereClause(filter, params);
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log" + where, Long.class, params.toArray());
    return total == null ? 0 : total;
  }

  private String buildWhereClause(Filter filter, List<Object> params) {
    List<String> conditions = new ArrayList<>();
    if (filter.actorUserId() != null) {
      conditions.add("actor_user_id = ?");
      params.add(filter.actorUserId());
    }
    if (filter.targetType() != null) {
      conditions.add("target_type = ?");
      params.add(filter.targetType());
    }
    if (filter.targetId() != null) {
      conditions.add("target_id = ?");
      params.add(filter.targetId());
    }
    if (filter.from() != null) {
      conditions.add("created_at >= ?");
      params.add(Timestamp.from(filter.from()));
    }
    if (filter.to() != null) {
      conditions.add("created_at <= ?");
      params.add(Timestamp.from(filter.to()));
    }
    return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
  }
}
