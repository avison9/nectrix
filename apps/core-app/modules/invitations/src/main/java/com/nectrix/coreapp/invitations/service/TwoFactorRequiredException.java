package com.nectrix.coreapp.invitations.service;

/**
 * Mandatory-2FA-before-trade-capable-linking gate (docs/17-security-architecture.md §17.3,
 * TICKET-110 AC1) — thrown when a linking-finalize call ({@code ctrader/link}, {@code mt5}, {@code
 * mt4}) arrives on a token whose {@code two_factor_enabled} claim is false. Applied unconditionally
 * to all three: the {@code connection_role} CHECK constraint (MASTER_ONLY/FOLLOWER_ONLY/BOTH) has
 * no read-only value to carve out, so there is no partial exemption to build. Mapped to 403 by
 * {@code BrokerAccountOAuthExceptionHandler} (applies package-wide — see that class's Javadoc).
 */
public class TwoFactorRequiredException extends RuntimeException {}
