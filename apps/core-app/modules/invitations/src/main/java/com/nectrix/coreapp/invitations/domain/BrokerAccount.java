package com.nectrix.coreapp.invitations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the `broker_accounts` table (docs/06-database-schema.md). TICKET-110 widens this from
 * TICKET-006's original read-only demo record to carry every column the API/UI layer needs: {@code
 * connectionRole} (MASTER_ONLY/FOLLOWER_ONLY/BOTH) and {@code openedViaIbLinkId} both already
 * existed in the DB schema since TICKET-004 but were never modeled in Java until now.
 */
public record BrokerAccount(
    UUID id,
    UUID userId,
    String brokerType,
    String brokerAccountLogin,
    String displayLabel,
    boolean isDemo,
    String currency,
    String connectionRole,
    UUID openedViaIbLinkId,
    String connectionStatus,
    Instant lastHealthCheckAt) {}
