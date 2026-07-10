package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.BrokerAccountAlreadyLinkedException;
import com.nectrix.coreapp.invitations.service.InvalidLinkSessionException;
import com.nectrix.coreapp.invitations.service.InvalidOAuthStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BrokerAccountOAuthController.class)
public class BrokerAccountOAuthExceptionHandler {

  @ExceptionHandler(InvalidOAuthStateException.class)
  public ResponseEntity<ErrorBody> handleInvalidState() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("invalid_oauth_state"));
  }

  @ExceptionHandler(InvalidLinkSessionException.class)
  public ResponseEntity<ErrorBody> handleInvalidLinkSession() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_link_session"));
  }

  @ExceptionHandler(BrokerAccountAlreadyLinkedException.class)
  public ResponseEntity<ErrorBody> handleAlreadyLinked() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("broker_account_already_linked"));
  }

  public record ErrorBody(String error) {}
}
