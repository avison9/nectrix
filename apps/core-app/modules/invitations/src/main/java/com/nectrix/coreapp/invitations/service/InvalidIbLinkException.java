package com.nectrix.coreapp.invitations.service;

/**
 * The {@code openedViaIbLinkId} on a link request didn't resolve to an existing, active {@code
 * broker_ib_links} row — mapped to 400 by {@code BrokerAccountOAuthExceptionHandler} (applies
 * package-wide, see that class's Javadoc).
 */
public class InvalidIbLinkException extends RuntimeException {}
