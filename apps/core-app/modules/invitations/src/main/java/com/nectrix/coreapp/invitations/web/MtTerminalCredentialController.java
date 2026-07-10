package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.MtTerminalCredentialService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Nectrix-hosted MT5/MT4 terminal provisioning — reachable only under {@code
 * /internal/broker-accounts/mt-terminal-credentials/**}, guarded by its OWN {@code
 * SecurityFilterChain} (SecurityConfig#internalMtTerminalCredentialsFilterChain), checking a
 * separate {@code MT_TERMINAL_PROVISIONER_TOKEN} — not the shared {@code X-Internal-Service-Token}
 * every other {@code /internal/**} caller (apps/broker-adapters, apps/mt5-bridge-gateway) uses.
 * Only apps/mt-terminal-host holds this token. See {@link MtTerminalCredentialService}'s Javadoc
 * for why this is a deliberately separate endpoint/class from the existing {@code mt-credentials}
 * one.
 */
@RestController
public class MtTerminalCredentialController {

  private final MtTerminalCredentialService service;

  public MtTerminalCredentialController(MtTerminalCredentialService service) {
    this.service = service;
  }

  @GetMapping("/internal/broker-accounts/mt-terminal-credentials/{id}")
  public MtTerminalCredentialsResponse mtTerminalCredentials(@PathVariable UUID id) {
    return MtTerminalCredentialsResponse.from(service.fetchMtTerminalCredentials(id));
  }

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record MtTerminalCredentialsResponse(
      String login, String password, String server, String pairingToken) {
    static MtTerminalCredentialsResponse from(
        MtTerminalCredentialService.DecryptedMtTerminalCredentials creds) {
      return new MtTerminalCredentialsResponse(
          creds.login(), creds.password(), creds.server(), creds.pairingToken());
    }
  }
}
