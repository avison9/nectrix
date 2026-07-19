package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.Invitation;
import com.nectrix.coreapp.invitations.service.InvitationService;
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
 * TICKET-118 — Master-scoped invitation CRUD (docs/14-api-specification.md §14.13). Every method is
 * scoped to the caller's OWN {@code master_profile_id}, resolved fresh from the JWT on every call —
 * never a client-supplied master id (see {@link InvitationService#requireMasterProfileId}
 * equivalent reasoning).
 */
@RestController
public class InvitationController {

  private final InvitationService service;

  public InvitationController(InvitationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/master/invitations")
  @PreAuthorize("hasRole('MASTER')")
  public Invitation create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
    return service.create(
        currentUserId(jwt),
        request.invitedEmail(),
        request.suggestedBrokerIbLinkId(),
        request.suggestedMoneyManagementProfileId(),
        request.suggestedRiskProfileId());
  }

  @GetMapping("/api/v1/master/invitations")
  @PreAuthorize("hasRole('MASTER')")
  public List<Invitation> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
    return service.listForMaster(currentUserId(jwt), status);
  }

  @PostMapping("/api/v1/master/invitations/{id}/revoke")
  @PreAuthorize("hasRole('MASTER')")
  public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.revoke(currentUserId(jwt), id);
    return ResponseEntity.noContent().build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record CreateRequest(
      String invitedEmail,
      UUID suggestedBrokerIbLinkId,
      UUID suggestedMoneyManagementProfileId,
      UUID suggestedRiskProfileId) {}
}
