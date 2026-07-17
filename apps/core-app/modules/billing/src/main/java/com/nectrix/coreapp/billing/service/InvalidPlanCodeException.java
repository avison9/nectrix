package com.nectrix.coreapp.billing.service;

/** Mapped to a clean 400 by {@code SubscriptionExceptionHandler} — never a bare NPE/KeyError. */
public class InvalidPlanCodeException extends RuntimeException {

  private final String planCode;

  public InvalidPlanCodeException(String planCode) {
    this.planCode = planCode;
  }

  public String planCode() {
    return planCode;
  }
}
