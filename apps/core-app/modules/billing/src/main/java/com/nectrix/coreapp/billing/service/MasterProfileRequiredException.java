package com.nectrix.coreapp.billing.service;

/**
 * The calling user has no {@code master_profiles} row — thrown by {@code POST /master/fee-reports}.
 */
public class MasterProfileRequiredException extends RuntimeException {}
