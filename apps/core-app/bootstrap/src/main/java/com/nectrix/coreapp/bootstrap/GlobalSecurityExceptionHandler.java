package com.nectrix.coreapp.bootstrap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * TICKET-006 — Spring Security's default {@code BearerTokenAccessDeniedHandler} already turns any
 * {@code AccessDeniedException} thrown by {@code @PreAuthorize}/{@code @PostAuthorize} (including
 * {@code AuthorizationDeniedException}, which extends it — the actual type Security 7's
 * AuthorizationManager-based interceptors throw) into a 403; this handler exists purely so the JSON
 * body shape matches {@code auth.web.AuthExceptionHandler}'s {@code {"error": "..."}} convention.
 * Lives in {@code bootstrap} (not any one module) since role/ownership checks span every module.
 */
@RestControllerAdvice
public class GlobalSecurityExceptionHandler {

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorBody> handleAccessDenied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorBody("forbidden"));
  }

  public record ErrorBody(String error) {}
}
