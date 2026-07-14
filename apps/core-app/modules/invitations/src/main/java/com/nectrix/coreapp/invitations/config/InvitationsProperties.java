package com.nectrix.coreapp.invitations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registered via {@code @EnableConfigurationProperties(InvitationsProperties.class)} — see
 * application.yml's {@code nectrix.invitations.*} block for the backing values. {@code
 * brokerAdapters.serviceToken} is the SAME value as {@code nectrix.internal.service-token} in
 * modules/auth's {@code SecurityConfig} (both resolve {@code INTERNAL_SERVICE_TOKEN}) — kept as two
 * separate properties bound to the same env var, not a shared config class, so neither module
 * depends on the other for configuration wiring (mirrors the "no cross-module deps" rule).
 */
@ConfigurationProperties(prefix = "nectrix.invitations")
public record InvitationsProperties(
    CtraderOauth ctraderOauth,
    BrokerAdapters brokerAdapters,
    TokenRefresh tokenRefresh,
    MtBridge mtBridge) {

  /**
   * clientId/clientSecret identify THIS platform's own registered cTrader Open API application
   * (docs/07-auth-onboarding-broker-linking.md §7.6) — never per-user.
   */
  public record CtraderOauth(String clientId, String clientSecret, String redirectUri) {}

  /**
   * Internal-only HTTP surface of apps/broker-adapters — see that service's internalapi package.
   */
  public record BrokerAdapters(String internalBaseUrl, String serviceToken) {}

  /**
   * TICKET-102 — the MT5/MT4 EA-bridge gateway (apps/mt5-bridge-gateway). {@code gatewayUrl} is the
   * WebSocket URL returned to the user at link time for them to paste into their EA's own input
   * parameters — a public-facing address (the user's own terminal must be able to reach it), unlike
   * {@code internalBaseUrl}/{@code serviceToken} below (TICKET-110 — cluster-internal snapshot/
   * positions passthrough, same shape as {@code brokerAdapters} above, matching apps/copy-engine's
   * own MT5_BRIDGE_GATEWAY_INTERNAL_BASE_URL env var precedent).
   */
  public record MtBridge(String gatewayUrl, String internalBaseUrl, String serviceToken) {}

  /**
   * TICKET-101 task #120 — TokenRefreshJob's own poll cadence and "nearing expiry" threshold.
   * Defaults are conservative placeholders (cTrader's real access-token TTL wasn't confirmed
   * against a live account during this ticket — see the live-verification runbook), not tuned
   * production values. {@code enabled} defaults to false: real deployments opt in explicitly
   * (deploy/base/core-app sets {@code TOKEN_REFRESH_ENABLED=true} — task #121) — with no per-test
   * Spring profile in this codebase, leaving this enabled by default would make every
   * {@code @SpringBootTest} (this module's own and every other module's) fire real outbound calls
   * to openapi.ctrader.com the moment any broker_accounts row exists, including pre-seeded dev-data
   * rows with no real ciphertext to decrypt at all.
   */
  public record TokenRefresh(
      boolean enabled, long intervalSeconds, long refreshBeforeExpirySeconds) {}
}
