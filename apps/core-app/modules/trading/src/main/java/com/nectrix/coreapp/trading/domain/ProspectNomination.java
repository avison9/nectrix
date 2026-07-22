package com.nectrix.coreapp.trading.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code prospect_nominations} table (029-prospect-nominations.sql) — the "Follower
 * refers a prospect, lands in their Master's inbox, Master sends a real TICKET-118 invitation"
 * flow. {@code invitationId} is set once the Master actually sends the invite (a separate action
 * from dismissing/reviewing — see {@code ProspectNominationService#markInvited}).
 */
public record ProspectNomination(
    UUID id,
    UUID masterProfileId,
    UUID nominatedByUserId,
    String prospectEmail,
    String status,
    UUID invitationId,
    Instant createdAt,
    Instant decidedAt) {}
