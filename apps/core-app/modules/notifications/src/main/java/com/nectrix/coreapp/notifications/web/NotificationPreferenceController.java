package com.nectrix.coreapp.notifications.web;

import com.nectrix.coreapp.notifications.domain.NotificationPreference;
import com.nectrix.coreapp.notifications.service.NotificationPreferenceService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** docs/14-api-specification.md §14.9 — per-user, per-event-type, per-channel preferences. */
@RestController
public class NotificationPreferenceController {

  private final NotificationPreferenceService service;

  public NotificationPreferenceController(NotificationPreferenceService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/notification-preferences")
  public List<NotificationPreference> list(@AuthenticationPrincipal Jwt jwt) {
    return service.findAllForUser(currentUserId(jwt));
  }

  /**
   * AC3 — rejecting an attempt to disable {@code drawdown.threshold_breached}'s {@code IN_APP}
   * channel is enforced inside {@link NotificationPreferenceService#update}, not just a UI default.
   */
  @PutMapping("/api/v1/notification-preferences")
  public ResponseEntity<Void> update(
      @AuthenticationPrincipal Jwt jwt, @RequestBody UpdateRequest request) {
    service.update(currentUserId(jwt), request.eventType(), request.channel(), request.enabled());
    return ResponseEntity.noContent().build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record UpdateRequest(String eventType, String channel, boolean enabled) {}
}
