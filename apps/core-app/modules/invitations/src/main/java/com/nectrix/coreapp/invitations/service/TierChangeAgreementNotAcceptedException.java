package com.nectrix.coreapp.invitations.service;

/**
 * TICKET-122 — thrown both at submission (the request body didn't accept the agreement) and again
 * at approval time as defense-in-depth (AC5: "an unsigned agreement blocks approval server-side,
 * not just as a UI gate") — the second check can only ever fire if some future code path manages to
 * insert a {@code tier_change_requests} row without going through {@link
 * TierChangeRequestService#submit}, but it's checked for real, not assumed.
 */
public class TierChangeAgreementNotAcceptedException extends RuntimeException {}
