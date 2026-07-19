package com.nectrix.coreapp.billing.service;

/**
 * No such row, or it exists but isn't the caller's own — same 404-either-way shape as a plain
 * not-found.
 */
public class FeeLedgerNotFoundException extends RuntimeException {}
