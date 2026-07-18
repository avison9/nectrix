package com.nectrix.coreapp.auth.service;

/**
 * {@code /2fa/enable} called on an account that already has 2FA active. Without this guard, {@link
 * TwoFactorService#beginEnrollment} would silently overwrite the confirmed secret with a fresh,
 * unconfirmed one and flip {@code two_factor_enabled} back to {@code false} — exactly what its own
 * Javadoc describes as safe for an *abandoned* enrollment, but not for a caller with a stale view
 * of an already-completed one (e.g. apps/web's `/2fa` settings page deciding which UI to render off
 * a JWT's `two_factor_enabled` claim, a snapshot as of token-issue time that can lag a few minutes
 * behind reality — see that page's own comment on `verifyTwoFactorAction`'s session refresh). Real
 * 2FA disablement is a separate, explicit action; this exception exists so re-enrolling can never
 * masquerade as one.
 */
public class TwoFactorAlreadyEnabledException extends RuntimeException {}
