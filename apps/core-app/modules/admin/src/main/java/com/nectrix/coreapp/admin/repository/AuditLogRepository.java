package com.nectrix.coreapp.admin.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes to `audit_log` (docs/17-security-architecture.md §17.6 — write-only at the app-DB-role
 * level, no UPDATE/DELETE grant, so even a compromised app credential can't rewrite history).
 *
 * <p>Scoped to {@code admin} module for now — the only writer this ticket needs (impersonation,
 * ledger-adjustment demo). Other modules will eventually need to write {@code audit_log} too (every
 * CopyRelationship state transition, every fee-ledger resolution per §17.6) — at that point this
 * should be extracted into a shared utility rather than duplicated per-module. Not built now
 * (YAGNI): same forward-reference-gap pattern as TICKET-005's rate limiter (built self-contained,
 * pending TICKET-008's shared helper).
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
}
