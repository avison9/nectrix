package com.nectrix.coreapp.bootstrap.archival;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to just {@link BrokerAccountArchivalController} via {@code assignableTypes} — {@code
 * invitations.web}'s own {@code BrokerAccountExceptionHandler} is itself scoped to {@code
 * invitations.web} via {@code basePackageClasses} and never sees exceptions thrown from this
 * bootstrap-owned controller.
 *
 * <p>Bugfix — this used to use {@code basePackageClasses}, which matched every controller in the
 * {@code bootstrap.archival} package, not just this one. That was harmless while this was the only
 * controller here; now that {@link MasterPrimaryBrokerAccountController} also lives in this package
 * (with its own, deliberately {@code assignableTypes}-scoped {@code
 * MasterPrimaryBrokerAccountExceptionHandler}), the old {@code basePackageClasses} scoping would
 * have ambiguously overlapped with it on {@code NoSuchElementException}.
 */
@RestControllerAdvice(assignableTypes = BrokerAccountArchivalController.class)
public class BrokerAccountArchivalExceptionHandler {

  /**
   * {@link com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi#getBrokerAccount}'s own
   * not-found contract.
   */
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("broker_account_not_found"));
  }

  /**
   * {@link BrokerAccountArchivalOrchestrator#archiveAndDelete}'s own not-{@code DISCONNECTED}
   * guard, and {@code BrokerAccountArchivalApiImpl#hardDelete}'s translated not-disconnected/
   * still-referenced cases — both genuinely unexpected once a caller has gone through the normal
   * disconnect-then-delete flow, so this is a defensive 409, not a routine one.
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorBody> handleNotReady(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("broker_account_not_ready_for_archival"));
  }

  /**
   * Bugfix — {@link BrokerAccountArchivalOrchestrator#archiveAndDelete}'s new pre-{@code
   * hardDelete} check: this account is a Master's only eligible primary, with no other account of
   * theirs to auto-reassign to first. Distinct from {@link #handleNotReady} — disconnecting
   * (already true here) doesn't fix this, linking another broker account does.
   */
  @ExceptionHandler(MasterPrimaryBrokerAccountRequiredException.class)
  public ResponseEntity<ErrorBody> handlePrimaryAccountRequired() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorBody("master_primary_broker_account_required"));
  }

  public record ErrorBody(String error) {}
}
