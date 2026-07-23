package com.nectrix.coreapp.trading.service;

/**
 * A broker account referenced by a copy-setup flow must belong to the expected user — thrown by
 * both {@code IndividualCopySetupService} (self-service same-user setup) and {@code
 * AdminCopyLinkService} (admin manual link, where the follower's chosen account must belong to
 * them).
 */
public class BrokerAccountNotOwnedException extends RuntimeException {}
