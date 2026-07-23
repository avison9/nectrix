package com.nectrix.coreapp.bootstrap.archival;

import com.nectrix.coreapp.social.api.PrimaryBrokerAccountChange;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bugfix — the self-service trigger for {@link MasterPrimaryBrokerAccountOrchestrator}. Lives in
 * {@code bootstrap}, not {@code social.web} alongside the rest of the master-profile routes, for
 * the same reason the orchestrator itself does (see that class's own Javadoc): it's the only place
 * that can reach {@code social}/{@code trading}'s own {@code ..api..} facades together.
 */
@RestController
public class MasterPrimaryBrokerAccountController {

  private final MasterPrimaryBrokerAccountOrchestrator orchestrator;

  public MasterPrimaryBrokerAccountController(MasterPrimaryBrokerAccountOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @PatchMapping("/api/v1/master-profiles/{id}/primary-broker-account")
  public PrimaryBrokerAccountChange changePrimaryBrokerAccount(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestBody ChangeRequest request) {
    UUID actingUserId = UUID.fromString(jwt.getSubject());
    return orchestrator.changePrimaryBrokerAccount(id, actingUserId, request.brokerAccountId());
  }

  public record ChangeRequest(UUID brokerAccountId) {}
}
