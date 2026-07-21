package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.BrokerIbLink;
import com.nectrix.coreapp.invitations.repository.BrokerIbLinkRepository;
import com.nectrix.coreapp.invitations.service.BrokerIbLinkService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/broker-accounts/ib-links} is TICKET-110's own narrow, unauthenticated-ownership
 * read (any authenticated user may look up a specific Master's active links by id — see that
 * endpoint's own Javadoc for why that's safe) for the "open a new account via IB link" onboarding
 * sub-flow; it stays as-is. The {@code /api/v1/master/broker-ib-links} routes below are
 * TICKET-119's own addition — real Master-scoped CRUD, same ownership-resolution shape as {@code
 * InvitationController}.
 */
@RestController
public class BrokerIbLinkController {

  private final BrokerIbLinkRepository repository;
  private final BrokerIbLinkService service;

  public BrokerIbLinkController(BrokerIbLinkRepository repository, BrokerIbLinkService service) {
    this.repository = repository;
    this.service = service;
  }

  @GetMapping("/api/v1/broker-accounts/ib-links")
  public List<BrokerIbLink> list(@RequestParam UUID masterProfileId) {
    return repository.findActiveForMaster(masterProfileId);
  }

  @PostMapping("/api/v1/master/broker-ib-links")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerIbLink create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
    return service.create(
        currentUserId(jwt),
        request.brokerType(),
        request.brokerDisplayName(),
        request.ibReferralUrlOrCode());
  }

  @GetMapping("/api/v1/master/broker-ib-links")
  @PreAuthorize("hasRole('MASTER')")
  public List<BrokerIbLink> listMine(@AuthenticationPrincipal Jwt jwt) {
    return service.listForMaster(currentUserId(jwt));
  }

  @PostMapping("/api/v1/master/broker-ib-links/{id}/deactivate")
  @PreAuthorize("hasRole('MASTER')")
  public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.deactivate(currentUserId(jwt), id);
    return ResponseEntity.noContent().build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record CreateRequest(
      String brokerType, String brokerDisplayName, String ibReferralUrlOrCode) {}
}
