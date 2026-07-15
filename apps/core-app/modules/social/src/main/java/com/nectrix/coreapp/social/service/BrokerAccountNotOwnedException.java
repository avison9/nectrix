package com.nectrix.coreapp.social.service;

/**
 * {@code primary_broker_account_id} either doesn't exist or isn't owned by the caller — mapped to
 * 403 by {@code MasterProfileExceptionHandler}, same "don't leak existence of someone else's
 * resource" posture as the rest of this codebase's IDOR handling.
 */
public class BrokerAccountNotOwnedException extends RuntimeException {}
