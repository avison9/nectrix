package com.nectrix.coreapp.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TICKET-101 — the shared secret Go (apps/broker-adapters) and Java authenticate to each other's
 * internal-only endpoints with. Registered via
 * {@code @EnableConfigurationProperties(InternalApiProperties.class)} on {@link SecurityConfig},
 * which also owns the {@code /internal/**}-scoped filter chain that checks it.
 */
@ConfigurationProperties(prefix = "nectrix.internal")
public record InternalApiProperties(String serviceToken) {}
