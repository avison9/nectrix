package com.nectrix.coreapp.social.web;

import com.nectrix.coreapp.social.service.BrokerAccountNotOwnedException;
import com.nectrix.coreapp.social.service.InvalidFeeCollectionMethodException;
import com.nectrix.coreapp.social.service.MasterProfileAlreadyExistsException;
import com.nectrix.coreapp.social.service.MasterProfileNotFoundException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = MasterProfileController.class)
public class MasterProfileExceptionHandler {

  @ExceptionHandler(MasterProfileNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("master_profile_not_found", null));
  }

  @ExceptionHandler(MasterProfileAlreadyExistsException.class)
  public ResponseEntity<ErrorBody> handleAlreadyExists(MasterProfileAlreadyExistsException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_profile_already_exists", e.existingProfileId()));
  }

  @ExceptionHandler(BrokerAccountNotOwnedException.class)
  public ResponseEntity<ErrorBody> handleBrokerAccountNotOwned() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorBody("broker_account_not_owned", null));
  }

  @ExceptionHandler(InvalidFeeCollectionMethodException.class)
  public ResponseEntity<ErrorBody> handleInvalidFeeCollectionMethod() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_fee_collection_method", null));
  }

  public record ErrorBody(String error, UUID existingProfileId) {}
}
