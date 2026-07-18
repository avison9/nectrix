package com.nectrix.coreapp.trading.api;

import java.util.UUID;

/**
 * The published shape of a {@code CopyRelationship} for cross-module callers — deliberately a
 * separate type from {@code trading.domain.CopyRelationship}, not a re-export of it, same
 * convention as {@code invitations.api.BrokerAccountView}. Carries only the fields a cross-module
 * ownership check needs (TICKET-116's WS {@code copy-relationships} channel subscribe check).
 */
public record CopyRelationshipView(UUID id, UUID followerUserId, String status) {}
