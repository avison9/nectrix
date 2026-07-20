package com.nectrix.coreapp.invitations.api;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.service.BrokerAccountInUseException;
import com.nectrix.coreapp.invitations.service.BrokerAccountNotDisconnectedException;
import com.nectrix.coreapp.invitations.service.BrokerAccountService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Injects {@link BrokerAccountRepository} directly rather than going through {@code
 * BrokerAccountService.getBrokerAccount}'s {@code @PostAuthorize} — this API is called by {@code
 * bootstrap}'s system-initiated orchestrator (both the on-demand endpoint, already authorized at
 * the controller layer against the real caller, and the scheduled sweep, which has no caller at
 * all), never by a per-request principal these methods could check ownership against. Same
 * "internal caller, skip the per-user auth annotation" precedent {@code
 * BrokerAccountInternalService} already established for apps/broker-adapters' own calls.
 */
@Service
public class BrokerAccountArchivalApiImpl implements BrokerAccountArchivalApi {

  private final BrokerAccountRepository repository;
  private final BrokerAccountService service;

  public BrokerAccountArchivalApiImpl(
      BrokerAccountRepository repository, BrokerAccountService service) {
    this.repository = repository;
    this.service = service;
  }

  @Override
  public BrokerAccountExportView findForExport(UUID id) {
    return toView(findOrThrow(id));
  }

  @Override
  public List<UUID> findStaleDisconnected(Duration olderThan) {
    return repository.findStaleDisconnectedIds(Instant.now().minus(olderThan));
  }

  @Override
  public void hardDelete(UUID id) {
    BrokerAccount existing = findOrThrow(id);
    try {
      service.deleteBrokerAccount(existing);
    } catch (BrokerAccountNotDisconnectedException | BrokerAccountInUseException e) {
      throw new IllegalStateException(
          "Broker account " + id + " was not ready for archival hard-delete: " + e.getMessage(), e);
    }
  }

  private BrokerAccount findOrThrow(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("No such broker account: " + id));
  }

  private BrokerAccountExportView toView(BrokerAccount account) {
    return new BrokerAccountExportView(
        account.id(),
        account.userId(),
        account.brokerType(),
        account.brokerAccountLogin(),
        account.displayLabel(),
        account.isDemo(),
        account.currency(),
        account.connectionRole(),
        account.openedViaIbLinkId(),
        account.connectionStatus(),
        account.lastHealthCheckAt(),
        account.brokerName(),
        account.serverName());
  }
}
