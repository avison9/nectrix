package com.nectrix.coreapp.invitations.service;

/**
 * A still-connected (or degraded/pending/reauth-required) {@code broker_accounts} row can't be
 * deleted directly — the caller must disconnect it first ({@code POST
 * /broker-accounts/{id}/disconnect}), an explicit, separate step rather than delete silently
 * disconnecting on the caller's behalf. Mapped to 409 by {@code BrokerAccountExceptionHandler}.
 */
public class BrokerAccountNotDisconnectedException extends RuntimeException {}
