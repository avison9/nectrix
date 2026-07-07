package com.nectrix.coreapp.auth.service.oauth;

/**
 * One implementation per provider (Google, Apple). OAuth is LOGIN-only here — this only ever
 * returns a provider-verified email address; it is AuthService's job (never this class's) to decide
 * whether that email maps to an existing account, and to reject rather than create one if it
 * doesn't (docs/07-auth-onboarding-broker-linking.md §7.1 — OAuth "log[s] in to an account that
 * already exists, never creates one").
 */
public interface OAuthProvider {

  /**
   * Exchanges an authorization code for the provider-verified email address. Throws if the
   * code/token is invalid or the email isn't verified.
   */
  String exchangeCodeForVerifiedEmail(String authorizationCode);
}
