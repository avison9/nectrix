package com.nectrix.coreapp.invitations.service;

/**
 * The {@code state} on a cTrader OAuth callback was missing, expired (>10 min), or already consumed
 * once — mapped to 400 by {@code BrokerAccountOAuthExceptionHandler}.
 */
public class InvalidOAuthStateException extends RuntimeException {}
