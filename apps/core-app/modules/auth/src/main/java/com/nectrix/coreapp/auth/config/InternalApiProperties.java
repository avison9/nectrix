package com.nectrix.coreapp.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TICKET-101 — {@code serviceToken} is the shared secret every general {@code /internal/**} caller
 * (apps/broker-adapters, apps/mt5-bridge-gateway) authenticates with. Registered via
 * {@code @EnableConfigurationProperties(InternalApiProperties.class)} on {@link SecurityConfig},
 * which also owns the {@code /internal/**}-scoped filter chain that checks it.
 *
 * <p>{@code mtTerminalProvisionerToken} is a deliberately SEPARATE secret — the Nectrix-hosted MT5/
 * MT4 terminal-provisioning work needs to fetch real plaintext broker passwords (see {@code
 * MtTerminalCredentialController}), a materially more sensitive capability than anything {@code
 * serviceToken}'s callers can do. Reusing {@code serviceToken} would mean a leak of the
 * broker-adapters/gateway token (already two live, WebSocket/broker-facing processes) could also
 * mint a raw-password fetch — this token keeps that blast radius genuinely separate, checked by its
 * own {@code SecurityFilterChain} (see {@link
 * SecurityConfig#internalMtTerminalCredentialsFilterChain}).
 */
@ConfigurationProperties(prefix = "nectrix.internal")
public record InternalApiProperties(String serviceToken, String mtTerminalProvisionerToken) {}
