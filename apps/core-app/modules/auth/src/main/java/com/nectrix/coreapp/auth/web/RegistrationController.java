package com.nectrix.coreapp.auth.web;

import com.nectrix.coreapp.auth.service.RegistrationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-114 — public self-serve "Individual" registration ({@code permitAll()} in {@link
 * com.nectrix.coreapp.auth.config.SecurityConfig}). No token issuance here — registration and login
 * stay decoupled; the frontend calls {@code POST /api/v1/auth/login} right after with the same
 * credentials, then (always, every plan requires a card on file) {@code POST /api/v1/subscriptions}
 * to start a Stripe Checkout session.
 */
@RestController
public class RegistrationController {

  private final RegistrationService registrationService;

  public RegistrationController(RegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @PostMapping("/api/v1/auth/register")
  public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
    UUID userId =
        registrationService.register(request.email(), request.password(), request.displayName());
    return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(userId));
  }

  public record RegisterRequest(String email, String password, String displayName) {}

  public record RegisterResponse(UUID userId) {}
}
