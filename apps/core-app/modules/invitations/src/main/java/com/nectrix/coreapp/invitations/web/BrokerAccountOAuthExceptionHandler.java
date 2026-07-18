package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.BrokerAccountAlreadyLinkedException;
import com.nectrix.coreapp.invitations.service.BrokerAccountLimitExceededException;
import com.nectrix.coreapp.invitations.service.InvalidConnectionRoleException;
import com.nectrix.coreapp.invitations.service.InvalidIbLinkException;
import com.nectrix.coreapp.invitations.service.InvalidLinkSessionException;
import com.nectrix.coreapp.invitations.service.InvalidOAuthStateException;
import com.nectrix.coreapp.invitations.service.TwoFactorRequiredException;
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

  @ExceptionHandler(InvalidIbLinkException.class)
  public ResponseEntity<ErrorBody> handleInvalidIbLink() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("invalid_ib_link"));
  }

  @ExceptionHandler(InvalidConnectionRoleException.class)
  public ResponseEntity<ErrorBody> handleInvalidConnectionRole() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_connection_role"));
  }

  @ExceptionHandler(TwoFactorRequiredException.class)
  public ResponseEntity<ErrorBody> handleTwoFactorRequired() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorBody("two_factor_required"));
  }

  /** TICKET-114 — a clear upgrade prompt, not a bare 403 (AC4's own wording). */
  @ExceptionHandler(BrokerAccountLimitExceededException.class)
  public ResponseEntity<LimitErrorBody> handleBrokerAccountLimitExceeded(
      BrokerAccountLimitExceededException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new LimitErrorBody("broker_account_limit_exceeded", e.connectionRole(), e.limit()));
  }

  public record ErrorBody(String error) {}

  public record LimitErrorBody(String error, String connectionRole, int limit) {}
}
