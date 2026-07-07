package com.nectrix.coreapp.auth.api;

/**
 * Deliberately has no refresh token — an impersonation session is a short-lived, single-purpose
 * grant (15 min, same TTL as a normal access token), not a renewable login; letting it be refreshed
 * indefinitely would defeat the point of it being short-lived and audited.
 */
public record ImpersonationResult(String accessToken, long expiresIn) {}
