package com.nectrix.coreapp.auth.domain;

/**
 * Returned by POST /auth/2fa/enable — the raw secret is shown once, for manual entry as a QR
 * fallback.
 */
public record TwoFactorEnrollment(String secret, String qrCodeUri) {}
