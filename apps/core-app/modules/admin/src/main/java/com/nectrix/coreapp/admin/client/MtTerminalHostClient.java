package com.nectrix.coreapp.admin.client;

import com.nectrix.coreapp.admin.config.AdminProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * TICKET-123 — calls apps/mt-terminal-host's new, deliberately read-only {@code GET
 * /internal/terminals/status} (see that service's {@code internal/terminalstatus} package's own doc
 * comment) — core-app's first outbound call to a Go service that isn't broker-adapters/
 * mt5-bridge-gateway, and the first time this monorepo's usual {@code /internal/**} direction (a Go
 * service calling INTO core-app) is reversed.
 */
@Service
public class MtTerminalHostClient {

  private final RestClient restClient = RestClient.create();
  private final AdminProperties.MtTerminalHost config;

  public MtTerminalHostClient(AdminProperties props) {
    this.config = props.mtTerminalHost();
  }

  /**
   * {@link Optional#empty()} means mt-terminal-host itself couldn't be reached at all (a real,
   * distinct failure mode from "reachable, but zero terminals currently exist" — an empty {@code
   * List}) — AdminController surfaces this distinction rather than collapsing both into the same
   * fabricated-zero outcome the ticket's own scope explicitly warns against.
   */
  public Optional<List<TerminalStatus>> listTerminalStatuses() {
    try {
      TerminalsResponse response =
          restClient
              .get()
              .uri(config.internalBaseUrl() + "/internal/terminals/status")
              .header("X-Internal-Service-Token", config.serviceToken())
              .retrieve()
              .body(TerminalsResponse.class);
      return Optional.of(
          response == null || response.terminals() == null ? List.of() : response.terminals());
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }

  private record TerminalsResponse(List<TerminalStatus> terminals) {}

  /**
   * Mirrors apps/mt-terminal-host's {@code terminalstatus.terminalStatusWire} JSON shape exactly.
   */
  public record TerminalStatus(
      String brokerAccountId,
      String podName,
      String phase,
      boolean ready,
      int restartCount,
      String waitingReason,
      String lastTransitionTime) {}
}
