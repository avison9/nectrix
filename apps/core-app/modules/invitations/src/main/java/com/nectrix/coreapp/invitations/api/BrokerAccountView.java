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
 */
public record BrokerAccountView(
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
