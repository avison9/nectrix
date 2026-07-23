package com.nectrix.coreapp.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TICKET-124 — {@code copyEngine} is apps/copy-engine's own internal-only HTTP surface (see that
 * service's {@code internal/httpapi} package), the same "cluster-internal, shared-secret-gated"
 * shape {@code invitations.config.InvitationsProperties.BrokerAdapters}/{@code MtBridge} already
 * establish for the Go services core-app calls into. {@code serviceToken} resolves the same {@code
 * INTERNAL_SERVICE_TOKEN} every other internal caller in this codebase already uses.
 */
@ConfigurationProperties(prefix = "nectrix.trading")
public record TradingProperties(CopyEngine copyEngine) {

  public record CopyEngine(String internalBaseUrl, String serviceToken) {}
}
