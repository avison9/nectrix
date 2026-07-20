package com.nectrix.coreapp.bootstrap.archival;

import com.nectrix.coreapp.bootstrap.archival.BrokerAccountArchivalOrchestrator.ArchivalResult;
import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-101 follow-up — the on-demand archival trigger. Lives in {@code bootstrap}, not {@code
 * invitations.web} alongside the rest of the broker-account routes, for the same reason {@link
 * BrokerAccountArchivalOrchestrator} itself does (see that class's own Javadoc): it's the only
 * place that can reach {@code invitations}/{@code trading}/{@code billing}'s own archival {@code
 * ..api..} facades together.
 *
 * <p>Uses {@link BrokerAccountLookupApi#getBrokerAccount} (not {@code
 * invitations.service.BrokerAccountService} directly, which returns {@code
 * invitations.domain.BrokerAccount} — {@code ModuleBoundaryArchTest} correctly rejects a
 * bootstrap-owned class depending on another module's {@code ..domain..} package) for the exact
 * same ownership/staff {@code @PostAuthorize} check every other {@code /broker-accounts/{id}/*}
 * route relies on — that API already wraps it and returns the plain {@link BrokerAccountView}.
 */
@RestController
public class BrokerAccountArchivalController {

  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final BrokerAccountArchivalOrchestrator orchestrator;

  public BrokerAccountArchivalController(
      BrokerAccountLookupApi brokerAccountLookupApi,
      BrokerAccountArchivalOrchestrator orchestrator) {
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.orchestrator = orchestrator;
  }

  @PostMapping("/api/v1/broker-accounts/{id}/archive-and-delete")
  public ArchivalResult archiveAndDelete(@PathVariable UUID id) {
    BrokerAccountView existing = brokerAccountLookupApi.getBrokerAccount(id);
    return orchestrator.archiveAndDelete(existing.id());
  }
}
