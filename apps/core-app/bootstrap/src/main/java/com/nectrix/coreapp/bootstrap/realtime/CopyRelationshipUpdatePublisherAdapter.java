package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.coreapp.trading.service.CopyRelationshipUpdatePublisher;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TICKET-116 — the concrete bean satisfying {@link CopyRelationshipUpdatePublisher}, delegating to
 * {@link BrokerConnectionWebSocketHandler#publishCopyRelationshipUpdate}. A separate adapter class
 * rather than having the handler itself {@code implements CopyRelationshipUpdatePublisher}: that
 * interface's {@code publish(UUID, String)} has the exact same erasure as {@code
 * InAppNotificationPublisher#publish(UUID, String)} (TICKET-115), which the handler already
 * implements for the unrelated {@code notifications} channel — one class can't provide two
 * different method bodies for the same erased signature.
 */
@Component
public class CopyRelationshipUpdatePublisherAdapter implements CopyRelationshipUpdatePublisher {

  private final BrokerConnectionWebSocketHandler webSocketHandler;

  public CopyRelationshipUpdatePublisherAdapter(BrokerConnectionWebSocketHandler webSocketHandler) {
    this.webSocketHandler = webSocketHandler;
  }

  @Override
  public void publish(UUID copyRelationshipId, String jsonPayload) {
    webSocketHandler.publishCopyRelationshipUpdate(copyRelationshipId.toString(), jsonPayload);
  }
}
