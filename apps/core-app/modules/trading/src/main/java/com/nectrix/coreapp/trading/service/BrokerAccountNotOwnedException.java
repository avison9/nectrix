package com.nectrix.coreapp.trading.service;

/** Both accounts in a self-service Individual copy setup must belong to the caller. */
public class BrokerAccountNotOwnedException extends RuntimeException {}
