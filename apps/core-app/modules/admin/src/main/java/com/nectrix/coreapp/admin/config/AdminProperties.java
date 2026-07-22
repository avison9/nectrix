package com.nectrix.coreapp.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registered via {@code @EnableConfigurationProperties(AdminProperties.class)} — see
 * application.yml's {@code nectrix.admin.*} block for the backing values. Mirrors {@code
 * invitations.config.InvitationsProperties}' own {@code BrokerAdapters}/{@code MtBridge} shape for
 * an internal-only Go-service client — same {@code internalBaseUrl}/{@code serviceToken} pair, same
 * {@code INTERNAL_SERVICE_TOKEN} value on both ends (Go and Java authenticate to each other with
 * it).
 */
@ConfigurationProperties(prefix = "nectrix.admin")
public record AdminProperties(MtTerminalHost mtTerminalHost) {

  /**
   * TICKET-123 — apps/mt-terminal-host's new, deliberately read-only {@code GET
   * /internal/terminals/status} (see that service's {@code internal/terminalstatus} package). This
   * is core-app's first outbound call to a Go service that ISN'T broker-adapters/mt5-bridge-gateway
   * — every prior {@code /internal/**} caller in this monorepo has a Go service calling INTO
   * core-app, never the reverse.
   */
  public record MtTerminalHost(String internalBaseUrl, String serviceToken) {}
}
