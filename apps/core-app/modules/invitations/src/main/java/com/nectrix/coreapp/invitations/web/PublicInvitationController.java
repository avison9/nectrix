package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.Invitation;
import com.nectrix.coreapp.invitations.repository.MasterProfileLookupRepository;
import com.nectrix.coreapp.invitations.service.InvitationRateLimitExceededException;
import com.nectrix.coreapp.invitations.service.InvitationRateLimiterService;
import com.nectrix.coreapp.invitations.service.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-118 — {@code GET /invitations/by-token/{token}} (docs/14-api-specification.md §14.2):
 * public, token-gated, just enough for the accept screen. Rate-limited to prevent token
 * brute-forcing (AC) — keyed by caller IP, since there's no authenticated subject yet to key by.
 */
@RestController
public class PublicInvitationController {

  private final InvitationService invitationService;
  private final InvitationRateLimiterService rateLimiterService;
  private final MasterProfileLookupRepository masterProfileLookupRepository;

  public PublicInvitationController(
      InvitationService invitationService,
      InvitationRateLimiterService rateLimiterService,
      MasterProfileLookupRepository masterProfileLookupRepository) {
    this.invitationService = invitationService;
    this.rateLimiterService = rateLimiterService;
    this.masterProfileLookupRepository = masterProfileLookupRepository;
  }

  @GetMapping("/api/v1/invitations/by-token/{token}")
  public InvitationPreview byToken(@PathVariable String token, HttpServletRequest request) {
    if (!rateLimiterService.tryConsume("by-token:" + request.getRemoteAddr())) {
      throw new InvitationRateLimitExceededException();
    }
    Invitation invitation = invitationService.validateByToken(token);
    String masterDisplayName =
        masterProfileLookupRepository.findDisplayName(invitation.masterProfileId()).orElse(null);
    return new InvitationPreview(
        invitation.id(), invitation.invitedEmail(), masterDisplayName, invitation.expiresAt());
  }

  public record InvitationPreview(
      UUID id, String invitedEmail, String masterDisplayName, java.time.Instant expiresAt) {}
}
