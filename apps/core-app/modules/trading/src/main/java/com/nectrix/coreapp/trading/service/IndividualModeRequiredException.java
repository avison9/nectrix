package com.nectrix.coreapp.trading.service;

/**
 * Thrown when a caller who already holds the {@code MASTER} or {@code FOLLOWER} role attempts the
 * self-service same-user copy-setup endpoint — that endpoint exists only for Individual-mode users;
 * real Masters/Followers use the existing invite-governed paths instead.
 */
public class IndividualModeRequiredException extends RuntimeException {}
