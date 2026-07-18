package com.nectrix.coreapp.bootstrap.notifications;

import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.BrokerConnectionEvent;
import com.nectrix.events.v1.BrokerConnectionEventType;
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
 * TICKET-115 — consumes {@code broker-connection} for {@code broker_connection.degraded/lost}
 * notifications. A SECOND, independent consumer group on the same topic {@code
 * BrokerConnectionEventConsumer} (TICKET-110) already reads — Kafka topics support multiple
 * independent consumer groups natively, no conflict; each maintains its own offsets for its own
 * purpose (that one drives the live WS status badge, this one drives notification delivery).
 */
@Component
public class BrokerConnectionNotificationConsumer {

  private static final Logger log =
      LoggerFactory.getLogger(BrokerConnectionNotificationConsumer.class);
  private static final String TOPIC = "broker-connection";
  private static final String DEFAULT_GROUP_ID = "core-app-notifications-broker-connection";

  private final IdempotentConsumer<BrokerConnectionEvent> consumer;
  private final Thread consumerThread;

  public BrokerConnectionNotificationConsumer(
      Environment env, UnifiedJedis redisClient, NotificationDispatchService dispatchService) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    String groupId =
        env.getProperty("NOTIFICATIONS_BROKER_CONNECTION_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<BrokerConnectionEvent> config =
        new IdempotentConsumer.Config<BrokerConnectionEvent>()
            .topic(TOPIC)
            .parser(BrokerConnectionEvent.parser())
            .keyExtractor(event -> event.getEnvelope().getEventId())
            .handler(event -> handle(event, dispatchService))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "notifications-broker-connection-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(BrokerConnectionEvent event, NotificationDispatchService dispatchService) {
    String eventType = eventTypeFor(event.getEventType());
    if (eventType == null) {
      return; // ESTABLISHED/REAUTH_REQUIRED aren't in the MVP notification set.
    }
    UUID brokerAccountId = UUID.fromString(event.getBrokerAccountId());
    dispatchService.dispatchForBrokerAccount(brokerAccountId, eventType, title(event), body(event));
  }

  private String eventTypeFor(BrokerConnectionEventType type) {
    return switch (type) {
      case BROKER_CONNECTION_EVENT_TYPE_DEGRADED ->
          NotificationEventTypes.BROKER_CONNECTION_DEGRADED;
      case BROKER_CONNECTION_EVENT_TYPE_LOST -> NotificationEventTypes.BROKER_CONNECTION_LOST;
      default -> null;
    };
  }

  private String title(BrokerConnectionEvent event) {
    return event.getEventType() == BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_LOST
        ? "Broker connection lost"
        : "Broker connection degraded";
  }

  private String body(BrokerConnectionEvent event) {
    if (event.hasDetail()) {
      return event.getDetail();
    }
    return event.getEventType() == BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_LOST
        ? "Reconnect your broker account to resume copying."
        : "Your broker account connection is degraded — trades may be delayed.";
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
    log.info("notifications: broker-connection consumer stopped");
  }
}
