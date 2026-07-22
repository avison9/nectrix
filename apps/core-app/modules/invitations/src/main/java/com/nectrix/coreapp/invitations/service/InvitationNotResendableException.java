package com.nectrix.coreapp.invitations.service;

/**
 * The invitation is {@code ACCEPTED} (already joined — nothing to resend) or {@code REVOKED} (the
 * Master explicitly cancelled it — create a new invitation instead of reviving this one). Mapped to
 * 409, distinct from {@link InvalidInvitationException}'s deliberate no-leak 400 since this is an
 * authenticated Master acting on their own invitation, not an anonymous token guess.
 */
public class InvitationNotResendableException extends RuntimeException {}
