package com.nectrix.coreapp.invitations.service;

/** TICKET-122 — the caller already has an unreviewed {@code PENDING} request. */
public class TierChangePendingRequestExistsException extends RuntimeException {}
