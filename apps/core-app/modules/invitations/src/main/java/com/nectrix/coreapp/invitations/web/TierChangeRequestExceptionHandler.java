package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.InvalidTierChangeTargetRoleException;
import com.nectrix.coreapp.invitations.service.TierChangeAgreementNotAcceptedException;
import com.nectrix.coreapp.invitations.service.TierChangeAlreadyHasRoleException;
import com.nectrix.coreapp.invitations.service.TierChangePendingRequestExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * TICKET-122. {@code TierChangeRequestNotFoundException} is only ever thrown on the admin-facing
 * path (modules:admin owns that mapping — see AdminExceptionHandler).
 */
@RestControllerAdvice(basePackageClasses = TierChangeRequestController.class)
public class TierChangeRequestExceptionHandler {

  @ExceptionHandler(InvalidTierChangeTargetRoleException.class)
  public ResponseEntity<ErrorBody> handleInvalidTargetRole() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("invalid_target_mode"));
  }

  @ExceptionHandler(TierChangeAlreadyHasRoleException.class)
  public ResponseEntity<ErrorBody> handleAlreadyHasRole() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("already_master_or_follower"));
  }

  @ExceptionHandler(TierChangePendingRequestExistsException.class)
  public ResponseEntity<ErrorBody> handlePendingExists() {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("pending_request_exists"));
  }

  @ExceptionHandler(TierChangeAgreementNotAcceptedException.class)
  public ResponseEntity<ErrorBody> handleAgreementNotAccepted() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("agreement_not_accepted"));
  }

  public record ErrorBody(String error) {}
}
