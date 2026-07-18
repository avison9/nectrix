package com.nectrix.coreapp.billing.repository;

import com.nectrix.coreapp.billing.domain.Subscription;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. TICKET-114 — a local
 * {@code subscriptions} row is only ever created by {@code StripeWebhookController} on {@code
 * checkout.session.completed} (never synchronously by {@code SubscriptionService.startCheckout},
 * which only returns a Checkout URL) — Stripe is the source of truth for whether the card on file
 * actually got collected.
 */
@Repository
public class SubscriptionRepository {

  private static final RowMapper<Subscription> ROW_MAPPER =
      (rs, rowNum) ->
          new Subscription(
              UUID.fromString(rs.getString("id")),
              UUID.fromString(rs.getString("user_id")),
              rs.getString("plan_code"),
              rs.getString("status"),
              rs.getTimestamp("current_period_start").toInstant(),
              rs.getTimestamp("current_period_end").toInstant(),
              rs.getString("stripe_subscription_id"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbcTemplate;

  public SubscriptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** The caller's current TRIALING/ACTIVE/PAST_DUE row, if any (CANCELED rows are excluded). */
  public Optional<Subscription> findActiveForUser(UUID userId) {
    return jdbcTemplate
        .query(
            """
            SELECT * FROM subscriptions
            WHERE user_id = ? AND status IN ('TRIALING','ACTIVE','PAST_DUE')
            ORDER BY created_at DESC LIMIT 1
            """,
            ROW_MAPPER,
            userId)
        .stream()
        .findFirst();
  }

  public Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM subscriptions WHERE stripe_subscription_id = ?",
            ROW_MAPPER,
            stripeSubscriptionId)
        .stream()
        .findFirst();
  }

  public UUID insert(
      UUID userId,
      String planCode,
      String status,
      Instant currentPeriodStart,
      Instant currentPeriodEnd,
      String stripeCustomerId,
      String stripeSubscriptionId) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO subscriptions
          (user_id, plan_code, status, current_period_start, current_period_end,
           payment_provider_customer_id, stripe_subscription_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        UUID.class,
        userId,
        planCode,
        status,
        Timestamp.from(currentPeriodStart),
        Timestamp.from(currentPeriodEnd),
        stripeCustomerId,
        stripeSubscriptionId);
  }

  /**
   * Webhook-driven status/period sync (checkout completed, trial->active, cancel-at-period-end).
   */
  public void updateStatusAndPeriod(
      String stripeSubscriptionId,
      String status,
      Instant currentPeriodStart,
      Instant currentPeriodEnd) {
    jdbcTemplate.update(
        "UPDATE subscriptions SET status = ?, current_period_start = ?, current_period_end = ? WHERE stripe_subscription_id = ?",
        status,
        Timestamp.from(currentPeriodStart),
        Timestamp.from(currentPeriodEnd),
        stripeSubscriptionId);
  }
}
