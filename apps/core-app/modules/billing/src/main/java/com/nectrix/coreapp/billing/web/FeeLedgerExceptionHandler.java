package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.service.FeeLedgerAlreadyDisputedException;
import com.nectrix.coreapp.billing.service.FeeLedgerNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = FeeLedgerController.class)
public class FeeLedgerExceptionHandler {

  @ExceptionHandler(FeeLedgerNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("not_found"));
  }

  @ExceptionHandler(FeeLedgerAlreadyDisputedException.class)
  public ResponseEntity<ErrorBody> handleAlreadyDisputed() {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("already_disputed"));
  }

  public record ErrorBody(String error) {}
}
