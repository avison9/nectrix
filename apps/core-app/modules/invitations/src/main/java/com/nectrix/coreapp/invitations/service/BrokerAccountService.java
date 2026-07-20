package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

/**
 * TICKET-006's object-level (IDOR-prevention) authorization demo, docs/17-security-architecture.md
 * §17.3 — reused by any future per-user-owned resource, not just BrokerAccount.
 *
 * <p>{@code @PostAuthorize} runs only after this method returns normally, so the 404 (not-found)
 * and 403 (not-yours) cases can never collide: an absent row throws before the SpEL check ever
 * runs. This pattern (declarative {@code @PostAuthorize} + a shared named bean referenced by SpEL)
 * is for reads only. TICKET-110's {@link #updateBrokerAccount}/{@link #deleteBrokerAccount} are
 * writes, so they deliberately carry NO {@code @PostAuthorize} of their own (Spring AOP proxies
 * don't intercept self-invocation, so a same-class call from here wouldn't even fire it) — callers
 * (see {@code BrokerAccountController}) must call {@link #getBrokerAccount} FIRST as an explicit
 * fetch-then-check-then-mutate guard, a genuine cross-bean call that the proxy does intercept,
 * before ever calling either mutating method.
 */
@Service
public class BrokerAccountService {

  private final BrokerAccountRepository repository;

  public BrokerAccountService(BrokerAccountRepository repository) {
    this.repository = repository;
  }

  @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")
  public BrokerAccount getBrokerAccount(UUID id) {
    return repository.findById(id).orElseThrow(BrokerAccountNotFoundException::new);
  }

  /** List endpoint's own query is scoped to the caller at the SQL layer — never a bare findAll. */
  public List<BrokerAccount> listForUser(UUID userId) {
    return repository.findAllForUser(userId);
  }

  /**
   * {@code existing} must already have passed {@link #getBrokerAccount}'s ownership check — see
   * this class's own Javadoc for why that can't happen inside this method itself.
   *
   * <p>TICKET-101 follow-up — {@code MASTER_ONLY} requires the caller to already hold the real,
   * onboarded {@code MASTER} role (see {@link MasterRoleRequiredException}'s own Javadoc); {@code
   * FOLLOWER_ONLY}/{@code BOTH} stay open to anyone, same as before.
   */
  public BrokerAccount updateBrokerAccount(
      BrokerAccount existing,
      String displayLabel,
      String connectionRole,
      List<String> callerRoles) {
    String resolvedLabel = displayLabel != null ? displayLabel : existing.displayLabel();
    String resolvedRole =
        ConnectionRoles.resolveOrDefault(
            connectionRole != null ? connectionRole : existing.connectionRole());
    if ("MASTER_ONLY".equals(resolvedRole)
        && (callerRoles == null || !callerRoles.contains("MASTER"))) {
      throw new MasterRoleRequiredException();
    }
    repository.updateDisplayLabelAndRole(existing.id(), resolvedLabel, resolvedRole);
    return repository.findById(existing.id()).orElseThrow(BrokerAccountNotFoundException::new);
  }

  /**
   * {@code existing} must already have passed {@link #getBrokerAccount}'s ownership check. A
   * self-service counterpart to {@link BrokerAccountInternalService#updateConnectionStatus} (which
   * only ever runs from apps/broker-adapters/the token-refresh job reporting real connection
   * health) — this is the user deliberately choosing to stop this account. Required before {@link
   * #deleteBrokerAccount} (see that method's own Javadoc) — disconnecting first, rather than
   * letting delete silently disconnect-then-remove in one step, gives the caller an explicit,
   * reversible-in-intent checkpoint before a permanent action.
   */
  public BrokerAccount disconnectBrokerAccount(BrokerAccount existing) {
    repository.updateConnectionStatus(existing.id(), "DISCONNECTED");
    return repository.findById(existing.id()).orElseThrow(BrokerAccountNotFoundException::new);
  }

  /**
   * {@code existing} must already have passed {@link #getBrokerAccount}'s ownership check.
   * broker_accounts has no ON DELETE CASCADE from copy_relationships — a still-referenced row's
   * DataIntegrityViolationException is translated to a clean 409, never a raw 500.
   *
   * <p>TICKET-101 follow-up — deleting a still-{@code CONNECTED} (or {@code DEGRADED}/{@code
   * PENDING}/{@code REAUTH_REQUIRED}) account is rejected; {@link #disconnectBrokerAccount} first
   * is mandatory, not optional — see {@link BrokerAccountNotDisconnectedException}'s own Javadoc.
   */
  public void deleteBrokerAccount(BrokerAccount existing) {
    if (!"DISCONNECTED".equals(existing.connectionStatus())) {
      throw new BrokerAccountNotDisconnectedException();
    }
    try {
      repository.delete(existing.id());
    } catch (DataIntegrityViolationException e) {
      throw new BrokerAccountInUseException();
    }
  }
}
