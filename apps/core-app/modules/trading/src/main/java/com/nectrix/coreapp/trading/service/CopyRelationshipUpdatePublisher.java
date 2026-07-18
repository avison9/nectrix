package com.nectrix.coreapp.trading.service;

import java.util.UUID;

/**
 * TICKET-116 — dependency-inversion seam, same pattern as {@code
 * notifications.service.InAppNotificationPublisher} (TICKET-115): {@code modules/trading} cannot
 * depend on {@code bootstrap} (bootstrap depends on every module, never the reverse). The real
 * implementation is bootstrap's {@code realtime.BrokerConnectionWebSocketHandler}, registered as
 * the Spring bean satisfying this interface — {@link CopyRelationshipService} only ever depends on
 * this abstraction, never a concrete WebSocket type.
 */
public interface CopyRelationshipUpdatePublisher {

  /**
   * No-op if nobody is currently subscribed to this relationship's channel — REST responses already
   * carry the new state, this is just the real-time nicety on top.
   */
  void publish(UUID copyRelationshipId, String jsonPayload);
}
