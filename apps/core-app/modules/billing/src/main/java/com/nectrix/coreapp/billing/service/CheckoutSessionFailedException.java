package com.nectrix.coreapp.billing.service;

/**
 * Wraps a {@link com.stripe.exception.StripeException} from Checkout Session creation or cancel.
 */
public class CheckoutSessionFailedException extends RuntimeException {

  public CheckoutSessionFailedException(Throwable cause) {
    super(cause);
  }
}
