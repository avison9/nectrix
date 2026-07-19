package com.nectrix.coreapp.billing.service;

/** Thrown when self-service dispute-raising targets a row that's already DISPUTED or VOID. */
public class FeeLedgerAlreadyDisputedException extends RuntimeException {}
