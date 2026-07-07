package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.service.BrokerAccountService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Any authenticated user may call this — ownership vs. staff-bypass is enforced by {@link
 * BrokerAccountService#getBrokerAccount}'s {@code @PostAuthorize}, not a route-level role check.
 */
@RestController
public class BrokerAccountController {

  private final BrokerAccountService service;

  public BrokerAccountController(BrokerAccountService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/broker-accounts/{id}")
  public BrokerAccount getById(@PathVariable UUID id) {
    return service.getBrokerAccount(id);
  }
}
