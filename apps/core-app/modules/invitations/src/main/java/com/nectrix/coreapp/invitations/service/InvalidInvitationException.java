package com.nectrix.coreapp.invitations.service;

/**
 * TICKET-118 — the one, deliberately generic error for every "this token doesn't work" case (not
 * found, expired, revoked, already accepted) — never leaks which case applies (AC: "fails clearly,
 * without leaking whether the email exists on the platform").
 */
public class InvalidInvitationException extends RuntimeException {}
