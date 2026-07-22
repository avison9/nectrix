package com.nectrix.coreapp.invitations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the `broker_accounts` table (docs/06-database-schema.md). TICKET-110 widens this from
 * TICKET-006's original read-only demo record to carry every column the API/UI layer needs: {@code
 * connectionRole} (MASTER_ONLY/FOLLOWER_ONLY/BOTH) and {@code openedViaIbLinkId} both already
 * existed in the DB schema since TICKET-004 but were never modeled in Java until now.
 *
 * <p>TICKET-101/102 follow-up — {@code brokerType} (CTRADER/MT5/MT4) is the PLATFORM, not the
 * actual broker's brand name (e.g. "Pepperstone") — {@code brokerName} was added because the UI was
 * conflating the two. {@code serverName} already existed as a {@code broker_accounts} DB column
 * since TICKET-004 but {@link
 * com.nectrix.coreapp.invitations.repository.BrokerAccountRepository#insert} never wrote it —
 * MT4/MT5 linking's own {@code MtLinkingService} captured the user's server value into {@code
 * request.server()} but only ever embedded it inside the ENCRYPTED credentials JSON, never as a
 * plain queryable column, so it never reached the API/UI layer until now.
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
    Instant lastHealthCheckAt,
    String brokerName,
    String serverName) {}
