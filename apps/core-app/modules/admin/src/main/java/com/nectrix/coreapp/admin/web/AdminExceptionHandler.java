package com.nectrix.coreapp.admin.web;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the plain {@link NoSuchElementException} thrown by both {@code ImpersonationApi} (no such
 * target user) and {@code BrokerAccountLookupApi} (no such broker account) to 404 — both
 * cross-module ..api.. surfaces deliberately throw only this standard JDK type, never a
 * module-internal exception class, so this handler needs no import from either module beyond {@code
 * AdminController} itself.
 */
@RestControllerAdvice(basePackageClasses = AdminController.class)
public class AdminExceptionHandler {

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("not_found"));
  }

  public record ErrorBody(String error) {}
}
