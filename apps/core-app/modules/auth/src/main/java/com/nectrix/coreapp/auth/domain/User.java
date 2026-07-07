package com.nectrix.coreapp.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** Mirrors the `users` table (docs/06-database-schema.md §6.2). */
public record User(
    UUID id,
    String email,
    String passwordHash, // nullable — OAuth-only users never set a password
    String displayName,
    boolean twoFactorEnabled,
    String
        twoFactorSecretCiphertext, // nullable; encrypted via TwoFactorSecretCipher, never plaintext
    String status,
    UUID createdByUserId,
    UUID createdViaInvitationId,
    UUID referredByUserId,
    String region,
    Instant createdAt,
    Instant updatedAt) {}
