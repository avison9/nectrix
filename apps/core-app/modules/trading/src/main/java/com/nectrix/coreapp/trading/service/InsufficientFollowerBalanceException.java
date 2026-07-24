package com.nectrix.coreapp.trading.service;

/**
 * Feature — thrown by {@code InvitationCopySetupService}/{@code AdminCopyLinkService} when a
 * would-be Follower's broker-account balance is below the Master's own configured {@code
 * min_follower_balance}. General onboarding (linking a broker account, etc.) is unaffected — this
 * only ever blocks the "start copying" action itself.
 */
public class InsufficientFollowerBalanceException extends RuntimeException {}
