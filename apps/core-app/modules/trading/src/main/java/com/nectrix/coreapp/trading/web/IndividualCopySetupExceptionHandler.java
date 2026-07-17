package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.service.BrokerAccountNotOwnedException;
import com.nectrix.coreapp.trading.service.IndividualModeRequiredException;
import com.nectrix.coreapp.trading.service.SameBrokerAccountException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = IndividualCopySetupController.class)
public class IndividualCopySetupExceptionHandler {

  @ExceptionHandler(IndividualModeRequiredException.class)
  public ResponseEntity<ErrorBody> handleIndividualModeRequired() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("individual_mode_required"));
  }

  @ExceptionHandler(BrokerAccountNotOwnedException.class)
  public ResponseEntity<ErrorBody> handleBrokerAccountNotOwned() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("broker_account_not_owned"));
  }

  @ExceptionHandler(SameBrokerAccountException.class)
  public ResponseEntity<ErrorBody> handleSameBrokerAccount() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("same_broker_account"));
  }

  public record ErrorBody(String error) {}
}
