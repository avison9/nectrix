package com.nectrix.coreapp.auth.service;

/**
 * TICKET-117 — {@code users.status != 'ACTIVE'} at the single choke point both {@code login()} and
 * {@code refresh()} funnel through ({@link AuthService#issueNewSession}), so a suspended user is
 * blocked from both a fresh login AND from using an existing, still-valid refresh token to mint a
 * new access token — checking only at login would leave a 30-day refresh token still able to renew
 * indefinitely.
 */
public class AccountSuspendedException extends RuntimeException {}
