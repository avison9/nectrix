package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.domain.Subscription;
import com.nectrix.coreapp.billing.service.SubscriptionNotFoundException;
import com.nectrix.coreapp.billing.service.SubscriptionService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** docs/14-api-specification.md §14.7 — the 3 subscription-lifecycle endpoints TICKET-114 owns. */
@RestController
public class SubscriptionController {

  private final SubscriptionService service;

  public SubscriptionController(SubscriptionService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/subscriptions")
  public CheckoutResponse subscribe(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SubscribeRequest request) {
    String checkoutUrl = service.startCheckout(currentUserId(jwt), request.planCode());
    return new CheckoutResponse(checkoutUrl);
  }

  @PostMapping("/api/v1/subscriptions/{id}/cancel")
  public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.cancel(currentUserId(jwt), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/subscriptions/me")
  public SubscriptionView getMine(@AuthenticationPrincipal Jwt jwt) {
    return service
        .getMine(currentUserId(jwt))
        .map(SubscriptionController::toView)
        .orElseThrow(SubscriptionNotFoundException::new);
  }

  private static SubscriptionView toView(Subscription s) {
    return new SubscriptionView(
        s.id(), s.planCode(), s.status(), s.currentPeriodStart(), s.currentPeriodEnd());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record SubscribeRequest(String planCode) {}

  public record CheckoutResponse(String checkoutUrl) {}

  public record SubscriptionView(
      UUID id,
      String planCode,
      String status,
      Instant currentPeriodStart,
      Instant currentPeriodEnd) {}
}
