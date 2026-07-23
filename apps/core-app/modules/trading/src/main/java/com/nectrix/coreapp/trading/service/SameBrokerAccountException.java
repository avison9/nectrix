package com.nectrix.coreapp.trading.service;

/**
 * The two broker accounts on either side of a copy relationship must be different — thrown by both
 * {@code IndividualCopySetupService} (main/slave must differ) and {@code AdminCopyLinkService} (the
 * follower's chosen account can't be the master's own primary account).
 */
public class SameBrokerAccountException extends RuntimeException {}
