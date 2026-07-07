package com.nectrix.coreapp.auth.service;

/**
 * OAuth is login-only — thrown when the provider-verified email has no matching existing account,
 * an unsupported provider is requested, or the provider's exchange itself fails. Never creates an
 * account.
 */
public class OAuthLoginRejectedException extends RuntimeException {
  public OAuthLoginRejectedException(String message) {
    super(message);
  }

  public OAuthLoginRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
