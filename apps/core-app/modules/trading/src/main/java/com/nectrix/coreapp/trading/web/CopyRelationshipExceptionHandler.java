package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.service.CopyRelationshipNotFoundException;
import com.nectrix.coreapp.trading.service.InvalidCopyRelationshipTransitionException;
import com.nectrix.coreapp.trading.service.ManagementAgreementNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = CopyRelationshipController.class)
public class CopyRelationshipExceptionHandler {

  @ExceptionHandler(CopyRelationshipNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("copy_relationship_not_found"));
  }

  @ExceptionHandler(ManagementAgreementNotFoundException.class)
  public ResponseEntity<ErrorBody> handleAgreementNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("management_agreement_not_found"));
  }

  @ExceptionHandler(InvalidCopyRelationshipTransitionException.class)
  public ResponseEntity<ErrorBody> handleInvalidTransition(
      InvalidCopyRelationshipTransitionException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("invalid_copy_relationship_transition: " + e.getMessage()));
  }

  public record ErrorBody(String error) {}
}
