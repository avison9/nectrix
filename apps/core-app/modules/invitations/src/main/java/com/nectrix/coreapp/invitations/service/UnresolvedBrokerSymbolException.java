package com.nectrix.coreapp.invitations.service;

/**
 * TICKET-116 — the manual symbol-mapping fallback's user-typed {@code brokerSymbolName} wasn't
 * recognized by a live round trip to the broker adapter ({@code BrokerAdaptersInternalClient
 * #resolveSymbol}). Mapped to 422 by {@code SymbolMappingExceptionHandler} — the request was
 * well-formed, but the broker itself rejected the symbol name.
 */
public class UnresolvedBrokerSymbolException extends RuntimeException {}
