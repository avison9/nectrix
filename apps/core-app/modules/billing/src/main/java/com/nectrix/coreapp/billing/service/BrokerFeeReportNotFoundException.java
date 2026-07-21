package com.nectrix.coreapp.billing.service;

/**
 * No such {@code broker_fee_reports} row, or it doesn't belong to the calling Master — mapped to
 * 404.
 */
public class BrokerFeeReportNotFoundException extends RuntimeException {}
