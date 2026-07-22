package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.service.MasterProfileRequiredException;
import com.nectrix.coreapp.trading.service.NoMasterToNominateToException;
import com.nectrix.coreapp.trading.service.ProspectNominationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ProspectNominationController.class)
public class ProspectNominationExceptionHandler {

  @ExceptionHandler(NoMasterToNominateToException.class)
  public ResponseEntity<ErrorBody> handleNoMaster() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("no_master_to_nominate_to"));
  }

  @ExceptionHandler(ProspectNominationNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("nomination_not_found"));
  }

  @ExceptionHandler(MasterProfileRequiredException.class)
  public ResponseEntity<ErrorBody> handleMasterProfileRequired() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_profile_required"));
  }

  public record ErrorBody(String error) {}
}
