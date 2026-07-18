package com.nectrix.coreapp.billing.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registered via {@code @EnableConfigurationProperties(BillingProperties.class)} — see
 * application.yml's {@code nectrix.billing.*} block. {@code platformTakeRatePct} is docs/11-
 * fee-engine-billing.md §11.7's "global default (simplest, MVP)" tier — a single platform-wide
 * scalar, not a config table; tiered/negotiated-per-master rates are explicitly a later phase
 * (§11.7's own "Enterprise phase" note).
 */
@ConfigurationProperties(prefix = "nectrix.billing")
public record BillingProperties(
    BigDecimal platformTakeRatePct, Stripe stripe, Subscriptions subscriptions) {

  /**
   * {@code webhookSigningSecret} verifies inbound {@code POST /internal/stripe/webhook} calls are
   * genuinely from Stripe (Stripe's own signature scheme), never trusted on payload content alone.
   */
  public record Stripe(String apiKey, String webhookSigningSecret) {}

  /**
   * TICKET-114 — Stripe Checkout Session config. {@code prices} maps {@link
   * com.nectrix.coreapp.billing.domain.SubscriptionPlans}' static catalog codes to real Stripe
   * Price ids (set per-environment; empty in dev/test, where Stripe calls are statically mocked).
   */
  public record Subscriptions(String successUrl, String cancelUrl, Prices prices) {

    public record Prices(String starter, String individual, String pro) {

      public String forPlanCode(String planCode) {
        return switch (planCode) {
          case "STARTER" -> starter;
          case "INDIVIDUAL" -> individual;
          case "PRO" -> pro;
          default ->
              throw new com.nectrix.coreapp.billing.service.InvalidPlanCodeException(planCode);
        };
      }
    }
  }
}
