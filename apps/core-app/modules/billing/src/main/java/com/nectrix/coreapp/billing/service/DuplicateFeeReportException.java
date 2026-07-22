package com.nectrix.coreapp.billing.service;

/**
 * A report for this exact (master, broker, period) already exists — {@code broker_fee_reports}' own
 * {@code UNIQUE} constraint catching a genuine duplicate-generation attempt, mapped to 409.
 */
public class DuplicateFeeReportException extends RuntimeException {}
