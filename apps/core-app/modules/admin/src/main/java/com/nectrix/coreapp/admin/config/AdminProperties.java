package com.nectrix.coreapp.admin.config;

import java.util.Map;
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
public record AdminProperties(
    MtTerminalHost mtTerminalHost,
    EngineService brokerAdapters,
    EngineService copyEngine,
    EngineService mtBridge,
    ServiceControl serviceControl) {

  /**
   * TICKET-123 — apps/mt-terminal-host's new, deliberately read-only {@code GET
   * /internal/terminals/status} (see that service's {@code internal/terminalstatus} package). This
   * is core-app's first outbound call to a Go service that ISN'T broker-adapters/mt5-bridge-gateway
   * — every prior {@code /internal/**} caller in this monorepo has a Go service calling INTO
   * core-app, never the reverse.
   */
  public record MtTerminalHost(String internalBaseUrl, String serviceToken) {}

  /**
   * Engine Control page — the shared {@code internalBaseUrl}/{@code serviceToken} shape for
   * broker-adapters/copy-engine/mt5-bridge-gateway's own {@code GET /internal/self/status} routes.
   * Declared again here (rather than reusing {@code invitations.config.InvitationsProperties}' or
   * {@code trading.config.TradingProperties}' own records for the same services) because
   * cross-module code only ever imports another module's {@code ..api..} package
   * (docs/04-architecture-overview.md §4.4) — a {@code config} record doesn't qualify, even though
   * every value here resolves the exact same env var as those modules' own properties.
   */
  public record EngineService(String internalBaseUrl, String serviceToken) {}

  /**
   * Local-Docker restart/stop/start capability (Engine Control page) — see {@code
   * DockerServiceControlClient}'s own Javadoc for why this defaults to disabled. {@code containers}
   * maps a fixed {@code serviceId} (broker-adapters/copy-engine/mt5-bridge-gateway/mt-terminal-host)
   * to the real {@code docker run --name} value it was started with locally — there's no
   * docker-compose-derived naming convention to infer this from.
   */
  public record ServiceControl(boolean enabled, Map<String, String> containers) {}
}
