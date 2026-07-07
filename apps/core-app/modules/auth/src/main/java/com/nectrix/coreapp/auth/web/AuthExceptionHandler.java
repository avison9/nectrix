package com.nectrix.coreapp.auth.web;

import com.nectrix.coreapp.auth.service.InvalidCredentialsException;
import com.nectrix.coreapp.auth.service.InvalidRefreshTokenException;
import com.nectrix.coreapp.auth.service.OAuthLoginRejectedException;
import com.nectrix.coreapp.auth.service.RateLimitExceededException;
import com.nectrix.coreapp.auth.service.TwoFactorRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link AuthController}'s domain exceptions to HTTP responses. {@code error} codes are
 * distinct even where the HTTP status is shared (e.g. both invalid-credentials and invalid-refresh
 * are 401) so a client can tell "wrong password" apart from "your session was revoked" without the
 * status code alone leaking which part of a login attempt was wrong (see each exception's own
 * Javadoc for the deliberate-uniformity reasoning).
 */
@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorBody> handleInvalidCredentials() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ErrorBody("invalid_credentials"));
  }

  @ExceptionHandler(TwoFactorRequiredException.class)
  public ResponseEntity<ErrorBody> handleTwoFactorRequired() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody("totp_required"));
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ErrorBody> handleRateLimitExceeded() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorBody("rate_limited"));
  }

  @ExceptionHandler(InvalidRefreshTokenException.class)
  public ResponseEntity<ErrorBody> handleInvalidRefreshToken() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ErrorBody("invalid_refresh_token"));
  }

  @ExceptionHandler(OAuthLoginRejectedException.class)
  public ResponseEntity<ErrorBody> handleOAuthLoginRejected() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ErrorBody("oauth_login_rejected"));
  }

  public record ErrorBody(String error) {}
}
