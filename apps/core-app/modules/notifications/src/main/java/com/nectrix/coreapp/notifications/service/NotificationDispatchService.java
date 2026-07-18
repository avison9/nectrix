package com.nectrix.coreapp.notifications.service;

import com.nectrix.coreapp.notifications.domain.NotificationEventTypes.Channel;
import com.nectrix.coreapp.notifications.repository.NotificationLogRepository;
import com.nectrix.coreapp.notifications.repository.NotificationTargetLookupRepository;
import com.nectrix.coreapp.notifications.repository.PushTokenRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-115 — the fan-out core, called by every Kafka consumer (bootstrap's {@code realtime}
 * package). Resolves the user's enabled channels (+ the drawdown force-override), writes a {@code
 * notification_log} row per attempted channel, delegates to the matching sender, then updates that
 * row's status. {@code IN_APP} always also pushes live over the WebSocket channel if a session is
 * currently connected — the log row is the durable/offline source of truth, the WS push is just the
 * real-time nicety on top.
 */
@Service
public class NotificationDispatchService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

  private final NotificationPreferenceService preferenceService;
  private final NotificationLogRepository logRepository;
  private final NotificationTargetLookupRepository targetLookupRepository;
  private final PushTokenRepository pushTokenRepository;
  private final PushSender pushSender;
  private final EmailSender emailSender;
  private final InAppNotificationPublisher inAppPublisher;
  private final ObjectMapper objectMapper;
  private final ObjectMapper wsObjectMapper = new ObjectMapper();

  public NotificationDispatchService(
      NotificationPreferenceService preferenceService,
      NotificationLogRepository logRepository,
      NotificationTargetLookupRepository targetLookupRepository,
      PushTokenRepository pushTokenRepository,
      PushSender pushSender,
      EmailSender emailSender,
      InAppNotificationPublisher inAppPublisher,
      ObjectMapper objectMapper) {
    this.preferenceService = preferenceService;
    this.logRepository = logRepository;
    this.targetLookupRepository = targetLookupRepository;
    this.pushTokenRepository = pushTokenRepository;
    this.pushSender = pushSender;
    this.emailSender = emailSender;
    this.inAppPublisher = inAppPublisher;
    this.objectMapper = objectMapper;
  }

  public void dispatch(UUID userId, String eventType, String title, String body) {
    dispatch(userId, eventType, title, body, false);
  }

  /**
   * Resolves the target user from a {@code copy_relationships} row first — the entry point for
   * {@code bootstrap.notifications}'s {@code CopiedTradeNotificationConsumer}/{@code
   * RiskNotificationConsumer}, which only ever have a {@code copy_relationship_id} from the event,
   * never a {@code userId} directly. Bootstrap can't reach {@link
   * NotificationTargetLookupRepository} itself (ArchUnit's module-boundary rule forbids any class
   * outside this module from touching its {@code ..repository..} package) — this method is the
   * sanctioned way in.
   */
  public void dispatchForCopyRelationship(
      UUID copyRelationshipId, String eventType, String title, String body) {
    dispatchForCopyRelationship(copyRelationshipId, eventType, title, body, false);
  }

  public void dispatchForCopyRelationship(
      UUID copyRelationshipId,
      String eventType,
      String title,
      String body,
      boolean forceInAppAndPush) {
    targetLookupRepository
        .findFollowerUserIdForCopyRelationship(copyRelationshipId)
        .ifPresentOrElse(
            userId -> dispatch(userId, eventType, title, body, forceInAppAndPush),
            () ->
                log.warn(
                    "notifications: no copy_relationships row for id={}, dropping event",
                    copyRelationshipId));
  }

  /**
   * Resolves the target user from a {@code broker_accounts} row first — the entry point for {@code
   * bootstrap.notifications}'s {@code BrokerConnectionNotificationConsumer}, same reasoning as
   * {@link #dispatchForCopyRelationship}.
   */
  public void dispatchForBrokerAccount(
      UUID brokerAccountId, String eventType, String title, String body) {
    targetLookupRepository
        .findUserIdForBrokerAccount(brokerAccountId)
        .ifPresentOrElse(
            userId -> dispatch(userId, eventType, title, body),
            () ->
                log.warn(
                    "notifications: no broker_accounts row for id={}, dropping event",
                    brokerAccountId));
  }

  /**
   * @param forceInAppAndPush the drawdown minimum-severity delivery-time override — a {@code
   *     RiskEvent.severity=FORCE_CLOSE} must always reach the user on IN_APP/PUSH regardless of a
   *     stale/misconfigured preference row (see NotificationPreferenceService's own Javadoc for the
   *     write-time half of this same rule).
   */
  public void dispatch(
      UUID userId, String eventType, String title, String body, boolean forceInAppAndPush) {
    Set<Channel> channels = preferenceService.resolveEnabledChannels(userId, eventType);
    if (forceInAppAndPush) {
      channels = EnumSet.copyOf(channels);
      channels.add(Channel.IN_APP);
      channels.add(Channel.PUSH);
    }

    String payloadJson = payloadJson(eventType, title, body);
    for (Channel channel : channels) {
      UUID logId = logRepository.insert(userId, eventType, channel.name(), payloadJson);
      boolean sent =
          switch (channel) {
            case IN_APP -> sendInApp(userId, eventType, title, body);
            case PUSH -> sendPush(userId, title, body);
            case EMAIL -> sendEmail(userId, title, body);
            case SMS -> false; // out of scope for MVP (ticket's own stated exclusion)
          };
      logRepository.updateStatus(logId, sent ? "SENT" : "FAILED", sent ? Instant.now() : null);
    }
  }

  private boolean sendInApp(UUID userId, String eventType, String title, String body) {
    WsNotification message = new WsNotification("notifications", eventType, title, body);
    inAppPublisher.publish(userId, wsObjectMapper.writeValueAsString(message));
    // IN_APP is always considered "sent" -- notification_log itself IS the durable delivery (the
    // WS push above is best-effort real-time on top, its absence isn't a delivery failure).
    return true;
  }

  private boolean sendPush(UUID userId, String title, String body) {
    List<String> tokens = pushTokenRepository.findTokensForUser(userId);
    if (tokens.isEmpty()) {
      log.debug("notifications: no push tokens registered for userId={}, skipping push", userId);
      return false;
    }
    boolean anySucceeded = false;
    for (String token : tokens) {
      if (pushSender.send(token, title, body)) {
        anySucceeded = true;
      }
    }
    return anySucceeded;
  }

  private boolean sendEmail(UUID userId, String title, String body) {
    return targetLookupRepository
        .findEmail(userId)
        .map(email -> emailSender.send(email, title, body))
        .orElse(false);
  }

  private String payloadJson(String eventType, String title, String body) {
    return objectMapper.writeValueAsString(new EventPayload(eventType, title, body));
  }

  private record EventPayload(String eventType, String title, String body) {}

  private record WsNotification(String channel, String eventType, String title, String body) {}
}
