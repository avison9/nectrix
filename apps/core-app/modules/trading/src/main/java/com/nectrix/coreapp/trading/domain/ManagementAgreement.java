package com.nectrix.coreapp.trading.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code management_agreements} table (007-billing.sql) — TICKET-120. Owned here, not
 * in {@code modules:billing}, because signing is driven entirely by {@link CopyRelationship}'s own
 * state machine ({@code PENDING_AGREEMENT -> ACTIVE}, see {@code CopyRelationshipService}) and
 * {@code copy_relationship_id} is trading's own table's primary key — same "read/write another
 * module's nominal table directly, no new module dependency" precedent {@code
 * billing.repository.ManagementAgreementRepository} already established in reverse (that one is a
 * separate, read-only repository for the archival flow's export — two independent JDBC access
 * points to the same table in different modules, by design, exactly like {@code billing}'s own
 * direct reads of {@code copy_relationships}).
 */
public record ManagementAgreement(
    UUID id,
    UUID copyRelationshipId,
    String agreementVersion,
    String status,
    String documentObjectKey,
    String signatureReference,
    Instant signedAt,
    Instant createdAt) {}
