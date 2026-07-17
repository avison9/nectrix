package com.nectrix.coreapp.notifications.service;

import java.util.UUID;

/**
 * TICKET-115 — dependency-inversion seam: {@code modules/notifications} cannot depend on {@code
 * bootstrap} (bootstrap depends on every module, never the reverse). The real implementation lives
 * in bootstrap's {@code realtime} package (the {@code notifications} WebSocket channel, alongside
 * TICKET-110's existing {@code broker-connection} one) and is registered as the Spring bean
 * satisfying this interface — {@link NotificationDispatchService} only ever depends on this
 * abstraction, never a concrete WebSocket type.
 */
public interface InAppNotificationPublisher {

  /**
   * No-op if the user has no currently-connected session — {@code notification_log} is the
   * durable/offline source of truth, this is just the real-time nicety on top.
   */
  void publish(UUID userId, String jsonPayload);
}
