package com.nectrix.coreapp.trading.service;

/**
 * The main and slave account in a self-service Individual copy setup must be different accounts.
 */
public class SameBrokerAccountException extends RuntimeException {}
