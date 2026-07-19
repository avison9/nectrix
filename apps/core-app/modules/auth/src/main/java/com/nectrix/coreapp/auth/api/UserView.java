package com.nectrix.coreapp.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module-sanctioned mirror of {@code auth.domain.User} — see {@code
 * invitations.api.BrokerAccountView}'s Javadoc for why this module keeps its own copy rather than
 * exporting the domain record directly. Deliberately omits {@code passwordHash}/{@code
 * twoFactorSecretCiphertext}/{@code twoFactorSecretKeyVersion} — no cross-module caller
 * (TICKET-117's admin user search/detail) has any legitimate reason to see them.
 */
public record UserView(
    UUID id,
    String email,
    String displayName,
    boolean twoFactorEnabled,
    String status,
    Instant createdAt) {}
