package com.nectrix.coreapp.invitations.service;

/**
 * This user already has a {@code broker_accounts} row for this broker_type+login combination —
 * mapped to 409 by {@code BrokerAccountOAuthExceptionHandler}. See {@link
 * com.nectrix.coreapp.invitations.repository.BrokerAccountRepository}'s Javadoc for why the DB's
 * own UNIQUE constraint can't be relied on to catch this.
 */
public class BrokerAccountAlreadyLinkedException extends RuntimeException {}
