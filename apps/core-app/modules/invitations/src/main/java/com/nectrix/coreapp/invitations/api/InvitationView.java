package com.nectrix.coreapp.invitations.api;

import java.util.UUID;

/**
 * The published shape of an {@code Invitation} for cross-module callers — deliberately a separate
 * type from {@code invitations.domain.Invitation}, not a re-export of it (same convention as {@code
 * trading.api.CopyRelationshipView}). Carries only the fields {@code trading}'s
 * invitation-acceptance flow needs.
 */
public record InvitationView(
    UUID id,
    UUID masterProfileId,
    String invitedEmail,
    String status,
    UUID suggestedMoneyManagementProfileId,
    UUID suggestedRiskProfileId,
    UUID acceptedByUserId) {}
