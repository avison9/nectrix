package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.service.InvitationCopySetupService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-118 — the two endpoints bridging {@code POST /auth/accept-invite} to a real {@code
 * CopyRelationship}: a convenience "is there anything for me to finish?" check, and the actual
 * creation step (after broker linking + reviewing suggested MM/risk defaults). See {@link
 * InvitationCopySetupService}'s own Javadoc for why this isn't folded into {@code
 * IndividualCopySetupController}.
 */
@RestController
public class InvitationCopySetupController {

  private final InvitationCopySetupService service;

  public InvitationCopySetupController(InvitationCopySetupService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/users/me/pending-invitation")
  public ResponseEntity<InvitationCopySetupService.PendingInvitation> pendingInvitation(
      @AuthenticationPrincipal Jwt jwt) {
    return service
        .getPendingInvitation(currentUserId(jwt))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/api/v1/copy-relationships/from-invitation")
  public CopyRelationship fromInvitation(
      @AuthenticationPrincipal Jwt jwt, @RequestBody FromInvitationRequest request) {
    return service.createFromInvitation(
        currentUserId(jwt),
        request.invitationId(),
        request.followerBrokerAccountId(),
        request.method(),
        request.fixedLotSize(),
        request.multiplier(),
        request.riskPercent(),
        request.roundingMode(),
        request.maxLotPerTrade(),
        request.maxOpenPositions(),
        request.maxSlippagePips());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  /**
   * Every field past {@code followerBrokerAccountId} is optional — omitted ones default to the
   * invitation's own suggested profile's values (see {@code InvitationCopySetupService}), same
   * shape as {@code CopyRelationshipController.CopySettingsRequest} (the existing in-place
   * copy-settings edit form), deliberately reused rather than inventing a parallel shape.
   */
  public record FromInvitationRequest(
      UUID invitationId,
      UUID followerBrokerAccountId,
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips) {}
}
