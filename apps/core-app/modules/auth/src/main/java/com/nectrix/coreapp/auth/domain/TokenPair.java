package com.nectrix.coreapp.auth.domain;

/**
 * Returned by login/refresh/oauth-callback — the JSON shape from docs/14-api-specification.md
 * §14.2. Field named {@code expiresIn} (not {@code expiresInSeconds}) deliberately — with the
 * app-wide SNAKE_CASE Jackson naming strategy, "Seconds" would otherwise leak into the wire shape
 * as {@code expires_in_seconds} instead of the spec's {@code expires_in}.
 */
public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
