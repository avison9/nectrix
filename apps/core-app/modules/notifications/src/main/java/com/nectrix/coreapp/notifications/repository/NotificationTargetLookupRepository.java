package com.nectrix.coreapp.notifications.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-115 — reads {@code copy_relationships}/{@code broker_accounts} directly via raw SQL (same
 * "read another module's table directly via SQL, not its Java repository class" precedent {@code
 * billing}'s own {@code SettlementDataRepository} already established) — a shared-database read,
 * not a Java package dependency, so it stays outside ArchUnit's module-boundary rule and needs no
 * {@code project()} dependency on {@code modules:trading}/{@code modules:invitations}.
 *
 * <p>{@code CopiedTradeEvent}/{@code RiskEvent} carry only {@code copy_relationship_id} (their
 * partition key), and {@code BrokerConnectionEvent} carries only {@code broker_account_id} — none
 * carry the target user id directly (unlike {@code BillingEvent}, which already has {@code
 * user_id}), so the notification consumers need this one small resolution step before they know who
 * to notify.
 */
@Repository
public class NotificationTargetLookupRepository {

  private final JdbcTemplate jdbcTemplate;

  public NotificationTargetLookupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<UUID> findFollowerUserIdForCopyRelationship(UUID copyRelationshipId) {
    return jdbcTemplate
        .query(
            "SELECT follower_user_id FROM copy_relationships WHERE id = ?",
            (rs, rowNum) -> UUID.fromString(rs.getString("follower_user_id")),
            copyRelationshipId)
        .stream()
        .findFirst();
  }

  public Optional<UUID> findUserIdForBrokerAccount(UUID brokerAccountId) {
    return jdbcTemplate
        .query(
            "SELECT user_id FROM broker_accounts WHERE id = ?",
            (rs, rowNum) -> UUID.fromString(rs.getString("user_id")),
            brokerAccountId)
        .stream()
        .findFirst();
  }

  /**
   * Needed for {@code EmailSender} — same precedent {@code billing}'s own SettlementDataRepository
   * already established for reading {@code users.email} directly.
   */
  public Optional<String> findEmail(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT email FROM users WHERE id = ?", (rs, rowNum) -> rs.getString("email"), userId)
        .stream()
        .findFirst();
  }
}
