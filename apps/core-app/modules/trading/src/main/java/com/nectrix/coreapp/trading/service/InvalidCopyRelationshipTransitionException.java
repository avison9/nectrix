package com.nectrix.coreapp.trading.service;

/**
 * A state-machine action was attempted from a status that doesn't permit it — e.g. {@code
 * sign-agreement} called while still {@code PENDING_RISK_ACK} (risk-ack not yet cleared), or {@code
 * resume} called on a relationship that isn't {@code PAUSED}. Mapped to 409 by {@code
 * CopyRelationshipExceptionHandler} — this is the server-side enforcement AC2/AC3 require (not just
 * a client-side UI guard).
 */
public class InvalidCopyRelationshipTransitionException extends RuntimeException {

  public InvalidCopyRelationshipTransitionException(String message) {
    super(message);
  }
}
