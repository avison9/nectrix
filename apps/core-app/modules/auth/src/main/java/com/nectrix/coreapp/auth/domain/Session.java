package com.nectrix.coreapp.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the `sessions` table (docs/06-database-schema.md §6.2 + TICKET-005's `revoked_reason`
 * migration). One row per issued refresh token — rotation replaces a row (marks it revoked, inserts
 * a new one), it never mutates `refresh_token_hash` in place, so a rotated-out row still exists to
 * detect reuse against.
 */
public record Session(
    UUID id,
    UUID userId,
    String refreshTokenHash,
    String deviceInfo, // raw JSON text or null
    String ipAddress,
    Instant createdAt,
    Instant expiresAt,
    Instant revokedAt, // null while active
    String revokedReason // LOGOUT | ROTATED | REUSE_DETECTED | EXPIRED | ADMIN_REVOKED | null
    ) {}
