package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.InvalidInvitationException;
import com.nectrix.coreapp.invitations.service.InvitationNotFoundException;
import com.nectrix.coreapp.invitations.service.InvitationRateLimitExceededException;
import com.nectrix.coreapp.invitations.service.MasterProfileRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    basePackageClasses = {
      InvitationController.class,
      PublicInvitationController.class,
      AcceptInviteController.class
    })
public class InvitationExceptionHandler {

  @ExceptionHandler(InvalidInvitationException.class)
  public ResponseEntity<ErrorBody> handleInvalid() {
    // Deliberately the same 400/generic body for not-found/expired/revoked/already-accepted — see
    // InvalidInvitationException's own Javadoc.
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_or_expired_invitation"));
  }

  @ExceptionHandler(InvitationNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("invitation_not_found"));
  }

  @ExceptionHandler(InvitationRateLimitExceededException.class)
  public ResponseEntity<ErrorBody> handleRateLimitExceeded() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorBody("rate_limited"));
  }

  @ExceptionHandler(MasterProfileRequiredException.class)
  public ResponseEntity<ErrorBody> handleMasterProfileRequired() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_profile_required"));
  }

  public record ErrorBody(String error) {}
}
