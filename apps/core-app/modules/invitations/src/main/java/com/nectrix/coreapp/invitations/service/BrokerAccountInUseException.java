package com.nectrix.coreapp.invitations.service;

/**
 * A {@code DELETE} was attempted on a {@code broker_accounts} row still referenced by a {@code
 * copy_relationships} row (no {@code ON DELETE CASCADE} between them, deliberately — deleting a
 * broker account out from under a live relationship must never silently orphan it) — mapped to 409
 * by {@code BrokerAccountExceptionHandler}. Currently unreachable in practice since TICKET-111
 * (CopyRelationship) isn't built yet, kept as a defensive guard for when it is.
 */
public class BrokerAccountInUseException extends RuntimeException {}
