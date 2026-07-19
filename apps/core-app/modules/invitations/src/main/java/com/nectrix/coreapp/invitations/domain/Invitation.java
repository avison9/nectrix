package com.nectrix.coreapp.invitations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code invitations} table (003-invitations-onboarding.sql) — TICKET-118. Only {@code
 * tokenHash} is ever persisted/returned from any repository read; the raw token exists only
 * transiently in {@code InvitationService#create}'s return value (for embedding in the emailed
 * link) and the client's in-flight accept-invite request — never logged, never stored a second
 * time anywhere (docs/17-security-architecture.md).
 */
public record Invitation(
    UUID id,
    UUID masterProfileId,
    String invitedEmail,
    String tokenHash,
    String status,
    UUID suggestedBrokerIbLinkId,
    UUID suggestedMoneyManagementProfileId,
    UUID suggestedRiskProfileId,
    UUID createdByUserId,
    Instant expiresAt,
    Instant acceptedAt,
    UUID acceptedByUserId,
    Instant createdAt) {}
