package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.TierChangeRequest;
import com.nectrix.coreapp.invitations.service.TierChangeRequestService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-122 — the self-service side (submit + view own status). The admin-facing list/approve/
 * reject routes live on {@code AdminController} (modules:admin), via {@code
 * invitations.api.TierChangeRequestAdminApi} — same split {@code BrokerAccountController} (self-
 * service) vs. {@code AdminController#getBrokerAccount} (staff lookup) already establishes.
 */
@RestController
public class TierChangeRequestController {

  private final TierChangeRequestService service;

  public TierChangeRequestController(TierChangeRequestService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/account/tier-change-requests")
  public ResponseEntity<TierChangeRequest> submit(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SubmitRequest request) {
    UUID userId = UUID.fromString(jwt.getSubject());
    List<String> roles = jwt.getClaimAsStringList("roles");
    TierChangeRequest created =
        service.submit(
            userId,
            request.targetMode(),
            roles != null ? roles : List.of(),
            request.agreementAccepted());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/api/v1/account/tier-change-requests/me")
  public ResponseEntity<TierChangeRequest> mine(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return service
        .getMine(userId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NO_CONTENT).build());
  }

  public record SubmitRequest(String targetMode, boolean agreementAccepted) {}
}
