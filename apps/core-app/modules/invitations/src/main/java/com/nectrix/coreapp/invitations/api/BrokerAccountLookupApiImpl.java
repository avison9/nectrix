package com.nectrix.coreapp.invitations.api;

import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.service.BrokerAccountNotFoundException;
import com.nectrix.coreapp.invitations.service.BrokerAccountService;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BrokerAccountLookupApiImpl implements BrokerAccountLookupApi {

  private final BrokerAccountService service;
  private final BrokerAccountRepository repository;
  private final BrokerAdaptersInternalClient brokerAdaptersClient;

  public BrokerAccountLookupApiImpl(
      BrokerAccountService service,
      BrokerAccountRepository repository,
      BrokerAdaptersInternalClient brokerAdaptersClient) {
    this.service = service;
    this.repository = repository;
    this.brokerAdaptersClient = brokerAdaptersClient;
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

  /**
   * Bugfix — deliberately bypasses {@link #getBrokerAccount}'s ownership-checked path: every real
   * caller here (the Master-side {@code ReturnPctEnrichmentService}, the activation-time min-
   * balance gate) is asking about an account that legitimately isn't the CALLER's own — a Master
   * reading a Follower's live equity, or an admin/follower already ownership-checked one layer up
   * via {@code BrokerAccountLookupApi#getBrokerAccount}/{@code lookupOwned} before ever reaching
   * here. Confirmed live: routing this through the {@code @PostAuthorize}-guarded {@code
   * service.getBrokerAccount} 403'd a Master's own followers-list read of a Follower's account.
   */
  @Override
  public AccountBalanceView getAccountBalance(UUID brokerAccountId) {
    BrokerAccount account =
        repository
            .findById(brokerAccountId)
            .orElseThrow(
                () -> new NoSuchElementException("No such broker account: " + brokerAccountId));
    BrokerAdaptersInternalClient.AccountSnapshot snapshot =
        brokerAdaptersClient.getAccountSnapshot(account.brokerType(), brokerAccountId.toString());
    return new AccountBalanceView(
        BigDecimal.valueOf(snapshot.balance()), BigDecimal.valueOf(snapshot.equity()));
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
