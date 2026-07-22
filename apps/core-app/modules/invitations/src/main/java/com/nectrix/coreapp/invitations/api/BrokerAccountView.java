package com.nectrix.coreapp.invitations.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The published shape of a {@code BrokerAccount} for cross-module callers — deliberately a separate
 * type from {@code invitations.domain.BrokerAccount}, not a re-export of it. Consumers outside this
 * module (e.g. {@code admin}'s staff-view endpoint) must never reference {@code
 * invitations.domain..} directly (enforced by {@code ModuleBoundaryArchTest}); returning the
 * internal domain record itself from an {@code ..api..} method still counts as leaking it (the type
 * appears in the caller's own method signature), so this module has its own mirror type here
 * instead.
 *
 * <p>Bugfix follow-up — {@code brokerType} (CTRADER/MT5/MT4) is the platform, not the brokerage
 * firm's own name; {@code brokerName} (nullable — see {@code
 * invitations.domain.BrokerAccount}'s own Javadoc) is that firm name, and was previously dropped
 * at this DTO boundary so admin's user-detail view had nothing to show but the platform.
 */
public record BrokerAccountView(
    UUID id,
    UUID userId,
    String brokerType,
    String brokerName,
    String brokerAccountLogin,
    String displayLabel,
    boolean isDemo,
    String currency,
    String connectionRole,
    UUID openedViaIbLinkId,
    String connectionStatus,
    Instant lastHealthCheckAt) {}
