package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.auth.api.AuthSessionApi;
import com.nectrix.coreapp.auth.api.TokenPairView;
import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.invitations.domain.Invitation;
import com.nectrix.coreapp.invitations.service.InvitationRateLimiterService;
import com.nectrix.coreapp.invitations.service.InvitationRateLimitExceededException;
import com.nectrix.coreapp.invitations.service.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-118 — {@code POST /auth/accept-invite} (docs/14-api-specification.md §14.2). Lives here,
 * not in {@code modules:auth}, so the business logic can call {@link InvitationService} in the
 * same module rather than a new cross-module surface just for this one endpoint — {@code
 * modules:auth} stays free of dependencies on other bounded-context modules (its own
 * build.gradle.kts's documented invariant); this module already needed a fresh, one-way {@code
 * invitations -> auth} edge anyway (see {@link UserProvisioningApi}/{@link AuthSessionApi}), and
 * the URL path itself doesn't care which module's controller serves it (same convention {@code
 * SecurityConfig}'s matcher list already follows for e.g. {@code /api/v1/individual/copy-setup}
 * living in {@code modules:trading}).
 */
@RestController
public class AcceptInviteController {

  private final InvitationService invitationService;
  private final InvitationRateLimiterService rateLimiterService;
  private final UserProvisioningApi userProvisioningApi;
  private final AuthSessionApi authSessionApi;
  private final ObjectMapper objectMapper;

  public AcceptInviteController(
      InvitationService invitationService,
      InvitationRateLimiterService rateLimiterService,
      UserProvisioningApi userProvisioningApi,
      AuthSessionApi authSessionApi,
      ObjectMapper objectMapper) {
    this.invitationService = invitationService;
    this.rateLimiterService = rateLimiterService;
    this.userProvisioningApi = userProvisioningApi;
    this.authSessionApi = authSessionApi;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/api/v1/auth/accept-invite")
  public TokenPairView acceptInvite(
      @RequestBody AcceptInviteRequest request, HttpServletRequest httpRequest) {
    if (!rateLimiterService.tryConsume("accept-invite:" + httpRequest.getRemoteAddr())) {
      throw new InvitationRateLimitExceededException();
    }
    Invitation invitation = invitationService.validateByToken(request.token());

    Optional<UUID> existingUserId =
        userProvisioningApi.findUserIdByEmail(invitation.invitedEmail());
    UUID userId;
    if (existingUserId.isPresent()) {
      // AC — a second Master's invite for an already-registered email must not create a second
      // User row; the existing account simply also becomes a Follower (grantRole is idempotent).
      userId = existingUserId.get();
    } else {
      String displayName =
          request.displayName() != null && !request.displayName().isBlank()
              ? request.displayName()
              : invitation.invitedEmail().split("@")[0];
      userId =
          userProvisioningApi.createUser(
              invitation.invitedEmail(),
              request.password(),
              displayName,
              null,
              invitation.id(),
              null,
              null);
    }
    userProvisioningApi.grantRole(userId, "FOLLOWER");
    invitationService.markAccepted(invitation.id(), userId);

    return authSessionApi.issueSession(
        userId, deviceInfoJson(httpRequest), httpRequest.getRemoteAddr());
  }

  private String deviceInfoJson(HttpServletRequest request) {
    return objectMapper.writeValueAsString(
        Map.of("user_agent", Objects.requireNonNullElse(request.getHeader("User-Agent"), "")));
  }

  public record AcceptInviteRequest(String token, String password, String displayName) {}
}
