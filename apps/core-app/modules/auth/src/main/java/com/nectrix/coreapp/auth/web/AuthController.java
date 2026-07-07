package com.nectrix.coreapp.auth.web;

import com.nectrix.coreapp.auth.domain.TokenPair;
import com.nectrix.coreapp.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/14-api-specification.md §14.2's login/refresh/logout/oauth-callback routes. No {@code
 * /auth/register} mapping exists here or anywhere else in this module — see {@code
 * UserProvisioningApi}'s Javadoc for why. Exception-to-status translation lives in {@link
 * AuthExceptionHandler}.
 */
@RestController
public class AuthController {

  private final AuthService authService;
  private final ObjectMapper objectMapper;

  public AuthController(AuthService authService, ObjectMapper objectMapper) {
    this.authService = authService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/api/v1/auth/login")
  public TokenPair login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    return authService.login(
        request.email(),
        request.password(),
        request.totpCode(),
        deviceInfoJson(httpRequest),
        clientIp(httpRequest));
  }

  @PostMapping("/api/v1/auth/oauth/{provider}/callback")
  public TokenPair oauthCallback(
      @PathVariable String provider,
      @RequestBody OAuthCallbackRequest request,
      HttpServletRequest httpRequest) {
    return authService.oauthCallback(
        provider, request.code(), deviceInfoJson(httpRequest), clientIp(httpRequest));
  }

  @PostMapping("/api/v1/auth/refresh")
  public TokenPair refresh(@RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
    return authService.refresh(
        request.refreshToken(), deviceInfoJson(httpRequest), clientIp(httpRequest));
  }

  @PostMapping("/api/v1/auth/logout")
  public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }

  private String deviceInfoJson(HttpServletRequest request) {
    // Jackson 3's writeValueAsString is unchecked (JacksonException extends
    // RuntimeException) — no try/catch needed for a plain Map<String,String>.
    return objectMapper.writeValueAsString(
        Map.of("user_agent", Objects.requireNonNullElse(request.getHeader("User-Agent"), "")));
  }

  private String clientIp(HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  public record LoginRequest(String email, String password, String totpCode) {}

  public record OAuthCallbackRequest(String code) {}

  public record RefreshRequest(String refreshToken) {}
}
