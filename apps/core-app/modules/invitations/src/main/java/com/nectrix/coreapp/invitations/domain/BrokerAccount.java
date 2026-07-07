package com.nectrix.coreapp.invitations.domain;

import java.util.UUID;

/**
 * Mirrors the `broker_accounts` table (docs/06-database-schema.md) — read-only for now (TICKET-006
 * only needs enough to demonstrate the object-ownership authorization pattern; real broker linking
 * is Phase 1, docs/07-auth-onboarding-broker-linking.md §7.4).
 */
public record BrokerAccount(
    UUID id,
    UUID userId,
    String brokerType,
    String brokerAccountLogin,
    String displayLabel,
    boolean isDemo,
    String currency,
    String connectionStatus) {}
