package com.nectrix.coreapp.invitations.api;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.service.BrokerAccountNotFoundException;
import com.nectrix.coreapp.invitations.service.BrokerAccountService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BrokerAccountLookupApiImpl implements BrokerAccountLookupApi {

  private final BrokerAccountService service;

  public BrokerAccountLookupApiImpl(BrokerAccountService service) {
    this.service = service;
  }

  @Override
  public BrokerAccountView getBrokerAccount(UUID id) {
    // Reuses the exact same @PostAuthorize-guarded method the Follower-facing
    // controller uses — an ADMIN/SUPPORT caller passes isOwnerOrStaff's staff
    // branch, a non-staff caller would still be correctly rejected even if
    // this API were ever called from somewhere without its own route-level
    // role check (defense in depth, not redundancy).
    BrokerAccount account;
    try {
      account = service.getBrokerAccount(id);
    } catch (BrokerAccountNotFoundException e) {
      // ..api.. surfaces only plain Java/domain types/exceptions (see
      // UserProvisioningApi's own Javadoc) — never a module-internal type
      // from ..service.. or ..web.., so callers in other modules never need
      // to import anything but this interface.
      throw new NoSuchElementException("No such broker account: " + id);
    }
    return new BrokerAccountView(
        account.id(),
        account.userId(),
        account.brokerType(),
        account.brokerName(),
        account.brokerAccountLogin(),
        account.displayLabel(),
        account.isDemo(),
        account.currency(),
        account.connectionRole(),
        account.openedViaIbLinkId(),
        account.connectionStatus(),
        account.lastHealthCheckAt());
  }

  @Override
  public List<BrokerAccountView> listForUser(UUID userId) {
    return service.listForUser(userId).stream().map(this::toView).toList();
  }

  private BrokerAccountView toView(BrokerAccount account) {
    return new BrokerAccountView(
        account.id(),
        account.userId(),
        account.brokerType(),
        account.brokerName(),
        account.brokerAccountLogin(),
        account.displayLabel(),
        account.isDemo(),
        account.currency(),
        account.connectionRole(),
        account.openedViaIbLinkId(),
        account.connectionStatus(),
        account.lastHealthCheckAt());
  }
}
