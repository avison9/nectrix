package com.nectrix.coreapp.notifications.web;

import com.nectrix.coreapp.notifications.repository.PushTokenRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-115 — device-token registration for push delivery. Not itself a documented
 * docs/14-api-specification.md endpoint (that spec predates this ticket's discovery that no
 * device-token schema existed anywhere — see 025-push-tokens.sql's own comment); a real client
 * registration flow (mobile app or web-push service worker) calling this is out of this ticket's
 * own scope, same as the raw-APNs reduction already agreed — this just gives {@code
 * NotificationDispatchService} somewhere real to read from.
 */
@RestController
public class PushTokenController {

  private final PushTokenRepository repository;

  public PushTokenController(PushTokenRepository repository) {
    this.repository = repository;
  }

  @PostMapping("/api/v1/push-tokens")
  public ResponseEntity<Void> register(
      @AuthenticationPrincipal Jwt jwt, @RequestBody RegisterRequest request) {
    repository.register(currentUserId(jwt), request.token(), request.platform());
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record RegisterRequest(String token, String platform) {}
}
