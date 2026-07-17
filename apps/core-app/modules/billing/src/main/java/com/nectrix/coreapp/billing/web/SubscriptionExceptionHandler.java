package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.service.CheckoutSessionFailedException;
import com.nectrix.coreapp.billing.service.InvalidPlanCodeException;
import com.nectrix.coreapp.billing.service.SubscriptionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = SubscriptionController.class)
public class SubscriptionExceptionHandler {

  @ExceptionHandler(InvalidPlanCodeException.class)
  public ResponseEntity<ErrorBody> handleInvalidPlanCode(InvalidPlanCodeException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_plan_code", e.planCode()));
  }

  @ExceptionHandler(SubscriptionNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("subscription_not_found", null));
  }

  @ExceptionHandler(CheckoutSessionFailedException.class)
  public ResponseEntity<ErrorBody> handleCheckoutSessionFailed() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorBody("checkout_session_failed", null));
  }

  public record ErrorBody(String error, String detail) {}
}
