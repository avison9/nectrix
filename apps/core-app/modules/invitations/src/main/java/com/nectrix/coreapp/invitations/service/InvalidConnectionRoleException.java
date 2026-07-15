package com.nectrix.coreapp.invitations.service;

/**
 * The {@code connectionRole} on a link/PATCH request wasn't one of MASTER_ONLY/FOLLOWER_ONLY/BOTH
 * (the `broker_accounts.connection_role` CHECK constraint's own values) — mapped to 400 by {@code
 * BrokerAccountOAuthExceptionHandler} (applies package-wide). Caught application-side so a bad
 * value surfaces as a clean 400, not a raw DataIntegrityViolationException/500 from the DB
 * constraint.
 */
public class InvalidConnectionRoleException extends RuntimeException {}
