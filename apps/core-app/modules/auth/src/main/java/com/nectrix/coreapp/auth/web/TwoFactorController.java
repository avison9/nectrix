package com.nectrix.coreapp.auth.web;

import com.nectrix.coreapp.auth.domain.TwoFactorEnrollment;
import com.nectrix.coreapp.auth.service.RateLimitExceededException;
import com.nectrix.coreapp.auth.service.RateLimiterService;
import com.nectrix.coreapp.auth.service.TwoFactorService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/14-api-specification.md §14.2's {@code /2fa/enable} and {@code /2fa/verify} — both require
 * an authenticated principal (enforced by SecurityConfig's explicit {@code .authenticated()}
 * matchers). The current user's id comes from the access token's {@code sub} claim, the same value
 * JwtService embeds at login (see JwtService#issueAccessToken). {@code /2fa/verify} is rate-limited
 * (see the plan's "Rate limiting" section — brute-forcing a pending enrollment's 6-digit TOTP code
 * is exactly the scenario that guard exists for) the same way {@code /login}'s embedded TOTP check
 * is.
 */
@RestController
public class TwoFactorController {

  private final TwoFactorService twoFactorService;
  private final RateLimiterService rateLimiterService;

  public TwoFactorController(
      TwoFactorService twoFactorService, RateLimiterService rateLimiterService) {
    this.twoFactorService = twoFactorService;
    this.rateLimiterService = rateLimiterService;
  }

  @PostMapping("/api/v1/auth/2fa/enable")
  public TwoFactorEnrollment enable(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = currentUserId(jwt);
    String email = jwt.getClaimAsString("email");
    return twoFactorService.beginEnrollment(userId, email);
  }

  @PostMapping("/api/v1/auth/2fa/verify")
  public ResponseEntity<Void> verify(
      @AuthenticationPrincipal Jwt jwt, @RequestBody VerifyRequest request) {
    UUID userId = currentUserId(jwt);
    if (!rateLimiterService.tryConsume("2fa-verify:" + userId)) {
      throw new RateLimitExceededException();
    }
    boolean confirmed = twoFactorService.confirmEnrollment(userId, request.totpCode());
    if (!confirmed) {
      return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
          .build();
    }
    return ResponseEntity.noContent().build();
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record VerifyRequest(String totpCode) {}
}
