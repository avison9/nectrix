package com.nectrix.coreapp.invitations.service;

/**
 * The {@code status} on a {@code POST /internal/broker-accounts/{id}/connection-status} call was
 * not one of the four real health-check-observable transitions
 * (CONNECTED/DEGRADED/DISCONNECTED/REAUTH_REQUIRED) — mapped to 400 by {@code
 * BrokerAccountInternalExceptionHandler}. PENDING is deliberately excluded: it's the row's own
 * creation-time default, never a real-time observation apps/broker-adapters would report.
 */
public class InvalidConnectionStatusException extends RuntimeException {}
