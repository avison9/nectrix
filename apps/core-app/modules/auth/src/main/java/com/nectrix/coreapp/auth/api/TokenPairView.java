package com.nectrix.coreapp.auth.api;

/**
 * The published shape of a {@code TokenPair} for cross-module callers — deliberately a separate
 * type from {@code auth.domain.TokenPair}, not a re-export of it (same convention as {@code
 * trading.api.CopyRelationshipView}).
 */
public record TokenPairView(String accessToken, String refreshToken, long expiresIn) {}
