package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.BrokerIbLinkNotFoundException;
import com.nectrix.coreapp.invitations.service.MasterProfileRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BrokerIbLinkController.class)
public class BrokerIbLinkExceptionHandler {

  @ExceptionHandler(BrokerIbLinkNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("broker_ib_link_not_found"));
  }

  @ExceptionHandler(MasterProfileRequiredException.class)
  public ResponseEntity<ErrorBody> handleMasterProfileRequired() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_profile_required"));
  }

  public record ErrorBody(String error) {}
}
