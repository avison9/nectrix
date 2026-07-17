package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.service.IndividualCopySetupService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-114 — self-serve "Individual" mode's copy setup: link a "main" broker account to a "slave"
 * one, both owned by the caller. Call once per slave account to fan out a main account to several
 * slaves. {@code IndividualModeRequiredException} (mapped by {@code
 * IndividualCopySetupExceptionHandler}) rejects a real Master/Follower caller — this endpoint is
 * Individual-mode only, unlike the invite-governed {@code CopyRelationshipController} paths.
 */
@RestController
public class IndividualCopySetupController {

  private final IndividualCopySetupService service;

  public IndividualCopySetupController(IndividualCopySetupService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/individual/copy-setup")
  public CopyRelationship setUp(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SetupRequest request) {
    return service.setUp(
        currentUserId(jwt),
        callerRoles(jwt),
        request.mainBrokerAccountId(),
        request.slaveBrokerAccountId());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private List<String> callerRoles(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return roles != null ? roles : List.of();
  }

  public record SetupRequest(UUID mainBrokerAccountId, UUID slaveBrokerAccountId) {}
}
