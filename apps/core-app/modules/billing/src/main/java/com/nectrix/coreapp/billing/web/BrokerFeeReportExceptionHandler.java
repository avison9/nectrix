package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.service.BrokerFeeReportNotFoundException;
import com.nectrix.coreapp.billing.service.DuplicateFeeReportException;
import com.nectrix.coreapp.billing.service.InvalidFeeReportTransitionException;
import com.nectrix.coreapp.billing.service.MasterProfileRequiredException;
import com.nectrix.coreapp.billing.service.NoPendingFeesToReportException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BrokerFeeReportController.class)
public class BrokerFeeReportExceptionHandler {

  @ExceptionHandler(BrokerFeeReportNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("broker_fee_report_not_found"));
  }

  @ExceptionHandler(DuplicateFeeReportException.class)
  public ResponseEntity<ErrorBody> handleDuplicate() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("broker_fee_report_already_exists_for_period"));
  }

  @ExceptionHandler(NoPendingFeesToReportException.class)
  public ResponseEntity<ErrorBody> handleNoPendingFees() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("no_pending_fees_to_report"));
  }

  @ExceptionHandler(InvalidFeeReportTransitionException.class)
  public ResponseEntity<ErrorBody> handleInvalidTransition(InvalidFeeReportTransitionException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("invalid_fee_report_transition: " + e.getMessage()));
  }

  @ExceptionHandler(MasterProfileRequiredException.class)
  public ResponseEntity<ErrorBody> handleMasterProfileRequired() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_profile_required"));
  }

  public record ErrorBody(String error) {}
}
