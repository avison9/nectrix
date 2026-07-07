package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.util.UUID;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

/**
 * TICKET-006's object-level (IDOR-prevention) authorization demo, docs/17-security-architecture.md
 * §17.3 — reused by any future per-user-owned resource, not just BrokerAccount.
 *
 * <p>{@code @PostAuthorize} runs only after this method returns normally, so the 404 (not-found)
 * and 403 (not-yours) cases can never collide: an absent row throws before the SpEL check ever
 * runs. This pattern (declarative {@code @PostAuthorize} + a shared named bean referenced by SpEL)
 * is for reads only — a hypothetical future write endpoint should use an explicit
 * fetch-then-check-then-mutate guard call instead, since {@code @PostAuthorize} runs after the
 * method body and its ordering relative to {@code @Transactional} isn't guaranteed.
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
}
