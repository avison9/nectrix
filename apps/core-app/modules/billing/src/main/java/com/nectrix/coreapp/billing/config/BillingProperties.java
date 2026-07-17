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
public record BillingProperties(BigDecimal platformTakeRatePct, Stripe stripe) {

  /**
   * {@code webhookSigningSecret} verifies inbound {@code POST /internal/stripe/webhook} calls are
   * genuinely from Stripe (Stripe's own signature scheme), never trusted on payload content alone.
   */
  public record Stripe(String apiKey, String webhookSigningSecret) {}
}
