package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.BrokerAccountInUseException;
import com.nectrix.coreapp.invitations.service.BrokerAccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BrokerAccountController.class)
public class BrokerAccountExceptionHandler {

  @ExceptionHandler(BrokerAccountNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("broker_account_not_found"));
  }

  @ExceptionHandler(BrokerAccountInUseException.class)
  public ResponseEntity<ErrorBody> handleInUse() {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("broker_account_in_use"));
  }

  public record ErrorBody(String error) {}
}
