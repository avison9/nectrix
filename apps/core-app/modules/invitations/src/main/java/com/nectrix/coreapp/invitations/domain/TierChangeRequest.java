package com.nectrix.coreapp.invitations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * TICKET-122 — mirrors {@code tier_change_requests} (033-tier-change-requests.sql). {@code
 * targetRole} is always {@code MASTER} or {@code FOLLOWER}; {@code status} starts {@code PENDING}
 * and transitions exactly once, to either {@code APPROVED} or {@code REJECTED}, via {@link
 * com.nectrix.coreapp.invitations.service.TierChangeRequestService}.
 */
public record TierChangeRequest(
    UUID id,
    UUID userId,
    String targetRole,
    String status,
    String agreementVersion,
    Instant agreementAcceptedAt,
    UUID reviewedByUserId,
    String reviewReason,
    Instant reviewedAt,
    Instant createdAt) {}
