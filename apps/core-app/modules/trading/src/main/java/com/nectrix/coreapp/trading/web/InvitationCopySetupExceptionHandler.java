package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.service.BrokerAccountNotOwnedException;
import com.nectrix.coreapp.trading.service.InsufficientFollowerBalanceException;
import com.nectrix.coreapp.trading.service.InvitationAlreadyUsedException;
import com.nectrix.coreapp.trading.service.InvitationNotAcceptedException;
import com.nectrix.coreapp.trading.service.InvitationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = InvitationCopySetupController.class)
public class InvitationCopySetupExceptionHandler {

  @ExceptionHandler(InvitationNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("invitation_not_found"));
  }

  @ExceptionHandler(InvitationNotAcceptedException.class)
  public ResponseEntity<ErrorBody> handleNotAccepted() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("invitation_not_accepted"));
  }

  @ExceptionHandler(InvitationAlreadyUsedException.class)
  public ResponseEntity<ErrorBody> handleAlreadyUsed() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("invitation_already_used"));
  }

  @ExceptionHandler(BrokerAccountNotOwnedException.class)
  public ResponseEntity<ErrorBody> handleBrokerAccountNotOwned() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("broker_account_not_owned"));
  }

  @ExceptionHandler(InsufficientFollowerBalanceException.class)
  public ResponseEntity<ErrorBody> handleInsufficientFollowerBalance() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("insufficient_follower_balance"));
  }

  public record ErrorBody(String error) {}
}
