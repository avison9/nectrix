package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.BrokerIbLink;
import com.nectrix.coreapp.invitations.repository.BrokerIbLinkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-110's own narrow, additive read for the "open a new account via IB link" sub-flow
 * (docs/07-auth-onboarding-broker-linking.md §7.4) — TICKET-119 (Broker IB Link creation/
 * management) isn't built yet, so this is deliberately read-only and minimal: just enough for this
 * ticket's UI to have a real data source instead of being unbuildable, trivially superseded/
 * deleted once TICKET-119 lands its own richer management endpoints. Any authenticated user may
 * call this (no ownership check — a Follower browsing their inviting Master's IB links doesn't own
 * them, but the data itself is not sensitive: it's the same referral URL/code the Master already
 * hands out externally).
 */
@RestController
public class BrokerIbLinkController {

  private final BrokerIbLinkRepository repository;

  public BrokerIbLinkController(BrokerIbLinkRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/v1/broker-accounts/ib-links")
  public List<BrokerIbLink> list(@RequestParam UUID masterProfileId) {
    return repository.findActiveForMaster(masterProfileId);
  }
}
