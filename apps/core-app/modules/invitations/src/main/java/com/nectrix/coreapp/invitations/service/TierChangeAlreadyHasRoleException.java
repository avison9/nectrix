package com.nectrix.coreapp.invitations.service;

/**
 * TICKET-122 — the caller already holds {@code MASTER} or {@code FOLLOWER}; nothing for a
 * tier-change request to change.
 */
public class TierChangeAlreadyHasRoleException extends RuntimeException {}
