package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.InvalidConnectionStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code BrokerAccountNotFoundException} is deliberately NOT handled here — {@link
 * BrokerAccountExceptionHandler} already does (both this class and that one are {@code
 * basePackageClasses}-scoped to the same {@code com.nectrix.coreapp.invitations.web} package, so
 * every {@code @RestControllerAdvice} in it applies to every controller in it; handling the same
 * exception type twice would make Spring's handler resolution ambiguous at request time).
 */
@RestControllerAdvice(basePackageClasses = BrokerAccountInternalController.class)
public class BrokerAccountInternalExceptionHandler {

  @ExceptionHandler(InvalidConnectionStatusException.class)
  public ResponseEntity<ErrorBody> handleInvalidStatus() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_connection_status"));
  }

  public record ErrorBody(String error) {}
}
