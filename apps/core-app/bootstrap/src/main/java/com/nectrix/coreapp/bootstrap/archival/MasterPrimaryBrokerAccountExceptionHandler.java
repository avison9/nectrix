package com.nectrix.coreapp.bootstrap.archival;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Bugfix — scoped to just {@link MasterPrimaryBrokerAccountController} via {@code assignableTypes}
 * (NOT {@code basePackageClasses}, which {@link BrokerAccountArchivalExceptionHandler} already uses
 * for the whole {@code bootstrap.archival} package — using it here too would make both advices
 * ambiguously overlap on this same {@code NoSuchElementException} type for every controller in the
 * package, including {@code BrokerAccountArchivalController}'s own). {@code AccessDeniedException}
 * (an unowned master profile or a broker account not owned by the caller) is already handled
 * globally — see {@code GlobalSecurityExceptionHandler}.
 */
@RestControllerAdvice(assignableTypes = MasterPrimaryBrokerAccountController.class)
public class MasterPrimaryBrokerAccountExceptionHandler {

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("not_found"));
  }

  public record ErrorBody(String error) {}
}
