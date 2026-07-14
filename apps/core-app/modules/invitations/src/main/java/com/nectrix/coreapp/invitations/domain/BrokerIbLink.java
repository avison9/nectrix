package com.nectrix.coreapp.invitations.domain;

import java.util.UUID;

/**
 * Mirrors the `broker_ib_links` table (docs/06-database-schema.md, TICKET-004). TICKET-110 only
 * needs a read path (validating `openedViaIbLinkId` at link time, and listing a Master's active
 * links for the "open a new account via IB link" sub-flow) — creation/management of these rows is
 * TICKET-119's own scope, not built here.
 */
public record BrokerIbLink(
    UUID id,
    UUID masterProfileId,
    String brokerType,
    String brokerDisplayName,
    String ibReferralUrlOrCode,
    boolean isActive) {}
