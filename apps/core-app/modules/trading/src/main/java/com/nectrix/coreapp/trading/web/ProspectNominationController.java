package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.domain.ProspectNomination;
import com.nectrix.coreapp.trading.service.ProspectNominationService;
import com.nectrix.coreapp.trading.service.ProspectNominationService.FollowerNominationView;
import com.nectrix.coreapp.trading.service.ProspectNominationService.NominationView;
import java.time.Instant;
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
 * TICKET-118 follow-up — see {@link ProspectNominationService}'s own Javadoc for the full flow.
 * Follower-facing create ({@code /follower/referrals}) and Master-facing review ({@code /inbox})
 * live in one controller since they're two sides of the same small flow, same convention {@code
 * InvitationController}'s own per-method {@code @PreAuthorize} already follows rather than
 * splitting by auth shape alone.
 */
@RestController
public class ProspectNominationController {

  private final ProspectNominationService service;

  public ProspectNominationController(ProspectNominationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/prospect-nominations")
  @PreAuthorize("hasRole('FOLLOWER')")
  public ProspectNomination nominate(
      @AuthenticationPrincipal Jwt jwt, @RequestBody NominateRequest request) {
    return service.nominate(currentUserId(jwt), request.prospectEmail());
  }

  @GetMapping("/api/v1/master/prospect-nominations")
  @PreAuthorize("hasRole('MASTER')")
  public List<NominationResponse> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
    return service.listForMaster(currentUserId(jwt), status).stream()
        .map(this::toResponse)
        .toList();
  }

  @GetMapping("/api/v1/prospect-nominations/mine")
  @PreAuthorize("hasRole('FOLLOWER')")
  public List<MyNominationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
    return service.listForFollower(currentUserId(jwt)).stream().map(this::toMyResponse).toList();
  }

  @PostMapping("/api/v1/master/prospect-nominations/{id}/mark-invited")
  @PreAuthorize("hasRole('MASTER')")
  public ResponseEntity<Void> markInvited(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestBody MarkInvitedRequest request) {
    service.markInvited(currentUserId(jwt), id, request.invitationId());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/master/prospect-nominations/{id}/dismiss")
  @PreAuthorize("hasRole('MASTER')")
  public ResponseEntity<Void> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.dismiss(currentUserId(jwt), id);
    return ResponseEntity.noContent().build();
  }

  private NominationResponse toResponse(NominationView view) {
    ProspectNomination n = view.nomination();
    return new NominationResponse(
        n.id(),
        n.prospectEmail(),
        n.status(),
        n.invitationId(),
        view.nominatedByDisplayName(),
        n.createdAt(),
        n.decidedAt());
  }

  private MyNominationResponse toMyResponse(FollowerNominationView view) {
    ProspectNomination n = view.nomination();
    return new MyNominationResponse(
        n.id(), n.prospectEmail(), view.followerFacingStatus(), n.createdAt(), n.decidedAt());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record NominateRequest(String prospectEmail) {}

  public record MarkInvitedRequest(UUID invitationId) {}

  public record NominationResponse(
      UUID id,
      String prospectEmail,
      String status,
      UUID invitationId,
      String nominatedByDisplayName,
      Instant createdAt,
      Instant decidedAt) {}

  /**
   * {@code status} here is the richer {@code SENT/INVITED/JOINED/DISMISSED} — see {@link
   * ProspectNominationService#listForFollower}.
   */
  public record MyNominationResponse(
      UUID id, String prospectEmail, String status, Instant createdAt, Instant decidedAt) {}
}
