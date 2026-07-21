package com.nectrix.coreapp.billing.service;

/** {@code send}/{@code confirm-deducted}/{@code confirm-paid} called out of the required order. */
public class InvalidFeeReportTransitionException extends RuntimeException {
  public InvalidFeeReportTransitionException(String message) {
    super(message);
  }
}
