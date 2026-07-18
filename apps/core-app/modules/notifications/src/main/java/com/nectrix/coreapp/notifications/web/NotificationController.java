package com.nectrix.coreapp.notifications.web;

import com.nectrix.coreapp.notifications.domain.NotificationLogEntry;
import com.nectrix.coreapp.notifications.service.NotificationInboxService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/14-api-specification.md §14.9 — the in-app notification inbox. */
@RestController
public class NotificationController {

  private final NotificationInboxService service;

  public NotificationController(NotificationInboxService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/notifications")
  public List<NotificationView> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Boolean unread) {
    return service.list(currentUserId(jwt), Boolean.TRUE.equals(unread)).stream()
        .map(NotificationController::toView)
        .toList();
  }

  @PostMapping("/api/v1/notifications/{id}/read")
  public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.markRead(id, currentUserId(jwt));
    return ResponseEntity.noContent().build();
  }

  private static NotificationView toView(NotificationLogEntry entry) {
    return new NotificationView(
        entry.id(), entry.eventType(), entry.payload(), entry.createdAt(), entry.readAt());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record NotificationView(
      UUID id, String eventType, String payload, Instant createdAt, Instant readAt) {}
}
