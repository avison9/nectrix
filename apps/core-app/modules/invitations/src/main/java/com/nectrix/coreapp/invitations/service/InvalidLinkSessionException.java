package com.nectrix.coreapp.invitations.service;

/**
 * The {@code linkSessionId} on a cTrader account-link request was missing, expired (>10 min),
 * already consumed once, or belonged to a different user than the caller — mapped to 400 by {@code
 * BrokerAccountOAuthExceptionHandler}. Deliberately one exception type for all three cases: leaking
 * which specific reason applied would let a caller probe for valid-but-expired vs. valid-but-wrong-
 * user session ids.
 */
public class InvalidLinkSessionException extends RuntimeException {}
