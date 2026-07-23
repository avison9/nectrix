package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app. */
@Repository
public class CopyRelationshipRepository {

  private static final RowMapper<CopyRelationship> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp riskAckAt = rs.getTimestamp("risk_ack_at");
        String originatingInvitationId = rs.getString("originating_invitation_id");
        String originatingFollowRequestId = rs.getString("originating_follow_request_id");
        Timestamp stoppedAt = rs.getTimestamp("stopped_at");
        // Feature — same java.sql.Array read pattern social/MasterProfileRepository's own
        // strategy_tags column already established (the one other TEXT[] column in this schema).
        Array excludedSymbolsArray = rs.getArray("excluded_symbols");
        List<String> excludedSymbols =
            excludedSymbolsArray != null
                ? List.of((String[]) excludedSymbolsArray.getArray())
                : List.of();
        return new CopyRelationship(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("master_profile_id")),
            UUID.fromString(rs.getString("master_broker_account_id")),
            UUID.fromString(rs.getString("follower_user_id")),
            UUID.fromString(rs.getString("follower_broker_account_id")),
            UUID.fromString(rs.getString("money_management_profile_id")),
            UUID.fromString(rs.getString("risk_profile_id")),
            rs.getString("status"),
            rs.getString("copy_direction"),
            rs.getBigDecimal("performance_fee_percent"),
            rs.getString("fee_collection_method"),
            rs.getBigDecimal("high_water_mark"),
            riskAckAt != null ? riskAckAt.toInstant() : null,
            originatingInvitationId != null ? UUID.fromString(originatingInvitationId) : null,
            originatingFollowRequestId != null ? UUID.fromString(originatingFollowRequestId) : null,
            rs.getTimestamp("created_at").toInstant(),
            stoppedAt != null ? stoppedAt.toInstant() : null,
            excludedSymbols,
            rs.getBigDecimal("starting_equity"));
      };

  private final JdbcTemplate jdbcTemplate;

  public CopyRelationshipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * TICKET-114 — the first real INSERT into this table: {@code IndividualCopySetupService}'s
   * self-service same-user copy setup. {@code originating_individual_setup=true} is the 3rd option
   * {@code chk_exactly_one_origin} was widened for (022-individual-mode-and-roles.sql) — TICKET-111
   * itself never creates a row (invite/follow-request-originated rows are TICKET-118/Phase-2
   * territory), so this stays the one path that does, deliberately narrow to that one origin.
   */
  public UUID insert(
      UUID masterProfileId,
      UUID masterBrokerAccountId,
      UUID followerUserId,
      UUID followerBrokerAccountId,
      UUID moneyManagementProfileId,
      UUID riskProfileId,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO copy_relationships
          (master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, performance_fee_percent,
           fee_collection_method, originating_individual_setup)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, true)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        moneyManagementProfileId,
        riskProfileId,
        performanceFeePercent,
        feeCollectionMethod);
  }

  /**
   * TICKET-118 — the second real INSERT into this table (alongside {@link #insert}'s
   * Individual-mode-only path): a Follower's invitation-acceptance flow, {@code
   * originating_invitation_id} set instead of {@code originating_individual_setup}, satisfying the
   * same {@code chk_exactly_one_origin} CHECK.
   */
  public UUID insertFromInvitation(
      UUID masterProfileId,
      UUID masterBrokerAccountId,
      UUID followerUserId,
      UUID followerBrokerAccountId,
      UUID moneyManagementProfileId,
      UUID riskProfileId,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod,
      String status,
      UUID originatingInvitationId,
      BigDecimal startingEquity) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO copy_relationships
          (master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, performance_fee_percent,
           fee_collection_method, status, originating_invitation_id, starting_equity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        moneyManagementProfileId,
        riskProfileId,
        performanceFeePercent,
        feeCollectionMethod,
        status,
        originatingInvitationId,
        startingEquity);
  }

  /**
   * Admin manual-link bypass ({@code AdminCopyLinkService}) — the third real INSERT into this
   * table. Like {@link #insertFromInvitation}, {@code status} is caller-chosen (risk-ack skipped,
   * broker-partnership agreement still required — see that service's own Javadoc), but {@code
   * originating_admin_action=true} instead of an invitation FK, satisfying {@code
   * chk_exactly_one_origin}'s 4th option (036-admin-manual-copy-link.sql).
   */
  public UUID insertAdminLinked(
      UUID masterProfileId,
      UUID masterBrokerAccountId,
      UUID followerUserId,
      UUID followerBrokerAccountId,
      UUID moneyManagementProfileId,
      UUID riskProfileId,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod,
      String status,
      BigDecimal startingEquity) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO copy_relationships
          (master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, performance_fee_percent,
           fee_collection_method, status, originating_admin_action, starting_equity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
        RETURNING id
        """,
        UUID.class,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        moneyManagementProfileId,
        riskProfileId,
        performanceFeePercent,
        feeCollectionMethod,
        status,
        startingEquity);
  }

  /**
   * Admin manual-link's own duplicate-prevention check — deliberately scoped to non-terminal
   * statuses ({@code STOPPED} relationships are intentionally re-linkable, same convention {@link
   * #reassignMasterBrokerAccount} already established for "still live" vs. "done").
   */
  public boolean existsActiveOrPendingForPair(
      UUID masterBrokerAccountId, UUID followerBrokerAccountId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM copy_relationships
            WHERE master_broker_account_id = ? AND follower_broker_account_id = ?
              AND status IN ('PENDING_RISK_ACK','PENDING_AGREEMENT','ACTIVE','PAUSED')
            """,
            Integer.class,
            masterBrokerAccountId,
            followerBrokerAccountId);
    return count != null && count > 0;
  }

  /** {@code GET /users/me/pending-invitation}'s own "has this already been actioned?" check. */
  public boolean existsByOriginatingInvitationId(UUID invitationId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM copy_relationships WHERE originating_invitation_id = ?",
            Integer.class,
            invitationId);
    return count != null && count > 0;
  }

  public Optional<CopyRelationship> findById(UUID id) {
    return jdbcTemplate
        .query("SELECT * FROM copy_relationships WHERE id = ?", ROW_MAPPER, id)
        .stream()
        .findFirst();
  }

  /**
   * {@code role="follower"}: rows where {@code follower_user_id = userId}. {@code role="master"}:
   * rows whose {@code master_profile_id} belongs to a {@code master_profiles} row this user owns —
   * a join, since {@code copy_relationships} has no direct {@code master_user_id} column of its
   * own. {@code status} is an optional filter (null = no filter).
   */
  public List<CopyRelationship> findAllForUser(UUID userId, String role, String status) {
    String base =
        "follower".equals(role)
            ? "SELECT cr.* FROM copy_relationships cr WHERE cr.follower_user_id = ?"
            : """
              SELECT cr.* FROM copy_relationships cr
              JOIN master_profiles mp ON mp.id = cr.master_profile_id
              WHERE mp.user_id = ?
              """;
    if (status == null) {
      return jdbcTemplate.query(base + " ORDER BY cr.created_at DESC", ROW_MAPPER, userId);
    }
    return jdbcTemplate.query(
        base + " AND cr.status = ? ORDER BY cr.created_at DESC", ROW_MAPPER, userId, status);
  }

  /** Sets {@code risk_ack_at} (idempotent — repeated calls just re-stamp "now"), always. */
  public void updateRiskAck(UUID id) {
    jdbcTemplate.update("UPDATE copy_relationships SET risk_ack_at = now() WHERE id = ?", id);
  }

  /** Plain status transition (PENDING_RISK_ACK->PENDING_AGREEMENT->ACTIVE, PAUSED<->ACTIVE). */
  public void updateStatus(UUID id, String status) {
    jdbcTemplate.update("UPDATE copy_relationships SET status = ? WHERE id = ?", status, id);
  }

  /** {@code stop} — terminal, records when. */
  public void markStopped(UUID id) {
    jdbcTemplate.update(
        "UPDATE copy_relationships SET status = 'STOPPED', stopped_at = now() WHERE id = ?", id);
  }

  /**
   * Feature — the Follower-editable per-relationship symbol EXCLUSION list (see {@code
   * CopyRelationship}'s own Javadoc for why exclusion-, not allow-, list). Caller is responsible
   * for normalizing (uppercase/trim/dedupe/drop-blank) before calling this — this method stores
   * exactly what it's given. Same {@code java.sql.Array} write pattern {@code
   * social.MasterProfileRepository#update}'s own {@code strategy_tags} column already established.
   */
  public void updateExcludedSymbols(UUID id, List<String> excludedSymbols) {
    jdbcTemplate.execute(
        "UPDATE copy_relationships SET excluded_symbols = ? WHERE id = ?",
        (PreparedStatement ps) -> {
          ps.setArray(1, ps.getConnection().createArrayOf("text", excludedSymbols.toArray()));
          ps.setObject(2, id);
          return ps.executeUpdate();
        });
  }

  /** {@code PATCH /copy-relationships/{id}} — swap in a different mm/risk profile. */
  public void updateProfiles(UUID id, UUID moneyManagementProfileId, UUID riskProfileId) {
    jdbcTemplate.update(
        """
        UPDATE copy_relationships
        SET money_management_profile_id = COALESCE(?, money_management_profile_id),
            risk_profile_id = COALESCE(?, risk_profile_id)
        WHERE id = ?
        """,
        moneyManagementProfileId,
        riskProfileId,
        id);
  }

  /**
   * Bugfix — when a Master changes their {@code master_profiles.primary_broker_account_id} (see
   * {@code bootstrap.archival.MasterPrimaryBrokerAccountOrchestrator}), any relationship still
   * pointing at the OLD account must be repointed too, or copy-engine's own {@code
   * matchRelationships} query (keyed on {@code master_broker_account_id}) can never match trades
   * from the new account again — the exact bug this method fixes. Deliberately scoped to
   * non-terminal statuses only ({@code STOPPED} relationships are done; there's no reason to touch
   * a row nothing will ever dispatch against again).
   */
  public void reassignMasterBrokerAccount(
      UUID masterProfileId, UUID oldBrokerAccountId, UUID newBrokerAccountId) {
    jdbcTemplate.update(
        """
        UPDATE copy_relationships
        SET master_broker_account_id = ?
        WHERE master_profile_id = ? AND master_broker_account_id = ?
          AND status IN ('ACTIVE','PENDING_RISK_ACK','PENDING_AGREEMENT')
        """,
        newBrokerAccountId,
        masterProfileId,
        oldBrokerAccountId);
  }

  /**
   * TICKET-101 follow-up — every relationship this account is party to, on either side (a {@code
   * BOTH}-role account can be a Master in some relationships and a Follower in others
   * simultaneously). Feeds the archival flow's export and cascade-delete.
   */
  public List<CopyRelationship> findAllByBrokerAccountId(UUID brokerAccountId) {
    return jdbcTemplate.query(
        """
        SELECT * FROM copy_relationships
        WHERE master_broker_account_id = ? OR follower_broker_account_id = ?
        """,
        ROW_MAPPER,
        brokerAccountId,
        brokerAccountId);
  }

  /**
   * Archival's own final step for this table — {@code management_agreements} auto-cascades via its
   * own {@code ON DELETE CASCADE}; {@code copied_trades}/{@code performance_fee_ledger} rows
   * referencing these ids must already be gone (see {@code CopyRelationshipArchivalApiImpl}'s own
   * ordering — neither has a cascade of its own).
   */
  public void deleteByIds(List<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    jdbcTemplate.update(
        "DELETE FROM copy_relationships WHERE id IN (" + placeholders + ")", ids.toArray());
  }

  /**
   * TICKET-124 — {@code UnrealizedPnlEnrichmentService}'s own batch lookup: for a page of open
   * {@code copied_trades} rows, resolve each row's relationship to the real follower broker
   * account/type it needs to call {@code BrokerAdaptersInternalClient.getOpenPositions} with.
   * Mirrors {@link #deleteByIds}'s own IN-list pattern.
   */
  public record FollowerAccountRef(
      UUID copyRelationshipId, UUID followerBrokerAccountId, String brokerType) {}

  public List<FollowerAccountRef> findFollowerAccountRefs(List<UUID> copyRelationshipIds) {
    if (copyRelationshipIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", copyRelationshipIds.stream().map(id -> "?").toList());
    return jdbcTemplate.query(
        "SELECT cr.id, cr.follower_broker_account_id, ba.broker_type "
            + "FROM copy_relationships cr JOIN broker_accounts ba ON ba.id = cr.follower_broker_account_id "
            + "WHERE cr.id IN ("
            + placeholders
            + ")",
        (rs, rowNum) ->
            new FollowerAccountRef(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("follower_broker_account_id")),
                rs.getString("broker_type")),
        copyRelationshipIds.toArray());
  }
}
