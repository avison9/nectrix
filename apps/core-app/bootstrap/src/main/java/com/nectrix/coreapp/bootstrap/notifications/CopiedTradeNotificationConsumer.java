package com.nectrix.coreapp.bootstrap.notifications;

import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.CopiedTradeEvent;
import com.nectrix.events.v1.CopiedTradeEventType;
import com.nectrix.redisclient.RedisDeduplicator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

/**
 * TICKET-115 — the real integration point {@code apps/copy-engine}'s {@code dispatch.go} (TICKET-
 * 106) own comment flagged: consumes {@code copied-trades} for {@code copied_trade.opened/closed/
 * failed} notifications. Own, independent consumer group on this topic (there is no other Java
 * consumer of it yet) — mirrors {@code BrokerConnectionEventConsumer}'s exact {@link
 * IdempotentConsumer}+{@link RedisDeduplicator} shape (TICKET-110).
 */
@Component
public class CopiedTradeNotificationConsumer {

  private static final Logger log = LoggerFactory.getLogger(CopiedTradeNotificationConsumer.class);
  private static final String TOPIC = "copied-trades";
  private static final String DEFAULT_GROUP_ID = "core-app-notifications-copied-trades";

  private final IdempotentConsumer<CopiedTradeEvent> consumer;
  private final Thread consumerThread;

  public CopiedTradeNotificationConsumer(
      Environment env, UnifiedJedis redisClient, NotificationDispatchService dispatchService) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    // See BrokerConnectionEventConsumer's identical comment: overridable so parallel
    // @SpringBootTest contexts each get their own consumer group, never racing over partitions.
    String groupId =
        env.getProperty("NOTIFICATIONS_COPIED_TRADES_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<CopiedTradeEvent> config =
        new IdempotentConsumer.Config<CopiedTradeEvent>()
            .topic(TOPIC)
            .parser(CopiedTradeEvent.parser())
            // Prefixed with this consumer's own DEFAULT_GROUP_ID, not the bare event id --
            // RedisDeduplicator's key is a global "events:dedup:<key>" namespace, and
            // CopiedTradePositionConsumer (TICKET-116) reads this exact same topic with the exact
            // same bare-eventId key shape; without a per-consumer prefix the two race for the same
            // Redis slot and whichever polls first silently causes the other to skip a real event
            // as a false "duplicate" (caught the hard way via a flaky
            // realCopiedTradeFailedEvent_producesARealNotificationLogRow() once the second
            // consumer landed).
            .keyExtractor(event -> DEFAULT_GROUP_ID + ":" + event.getEnvelope().getEventId())
            .handler(event -> handle(event, dispatchService))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "notifications-copied-trades-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(CopiedTradeEvent event, NotificationDispatchService dispatchService) {
    String eventType = eventTypeFor(event.getEventType());
    if (eventType == null) {
      return; // MODIFIED/PARTIALLY_CLOSED aren't in the MVP notification set.
    }
    UUID copyRelationshipId = UUID.fromString(event.getCopyRelationshipId());
    dispatchService.dispatchForCopyRelationship(
        copyRelationshipId, eventType, title(event), body(event));
  }

  private String eventTypeFor(CopiedTradeEventType type) {
    return switch (type) {
      case COPIED_TRADE_EVENT_TYPE_OPENED -> NotificationEventTypes.COPIED_TRADE_OPENED;
      case COPIED_TRADE_EVENT_TYPE_CLOSED -> NotificationEventTypes.COPIED_TRADE_CLOSED;
      case COPIED_TRADE_EVENT_TYPE_FAILED -> NotificationEventTypes.COPIED_TRADE_FAILED;
      default -> null;
    };
  }

  private String title(CopiedTradeEvent event) {
    return switch (event.getEventType()) {
      case COPIED_TRADE_EVENT_TYPE_OPENED -> "Trade copied";
      case COPIED_TRADE_EVENT_TYPE_CLOSED -> "Trade closed";
      case COPIED_TRADE_EVENT_TYPE_FAILED -> "Trade copy failed";
      default -> "Trade update";
    };
  }

  private String body(CopiedTradeEvent event) {
    return switch (event.getEventType()) {
      case COPIED_TRADE_EVENT_TYPE_OPENED -> "A new position was opened on your account.";
      case COPIED_TRADE_EVENT_TYPE_CLOSED -> "A copied position was closed.";
      case COPIED_TRADE_EVENT_TYPE_FAILED ->
          event.hasRejectReason()
              ? "A trade could not be copied: " + event.getRejectReason()
              : "A trade could not be copied.";
      default -> "";
    };
  }

  @PreDestroy
  public void shutdown() {
    consumer.stop();
    try {
      consumerThread.join(Duration.ofSeconds(5).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    consumer.close();
    log.info("notifications: copied-trades consumer stopped");
  }
}
