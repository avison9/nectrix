package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.CopiedTradeEvent;
import com.nectrix.redisclient.RedisDeduplicator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-116 — docs/14-api-specification.md §14.11's {@code copy-relationships.{id}} channel,
 * follower side's "new copied trades" half (the other half — status changes — is pushed
 * synchronously by {@code CopyRelationshipService} itself, see {@code
 * CopyRelationshipUpdatePublisherAdapter}'s own Javadoc). Consumes {@code copied-trades} — a THIRD
 * independent consumer group on this topic, alongside {@code CopiedTradeNotificationConsumer}
 * (TICKET-115) and the Copy Engine's own internal consumers; same "Kafka topics support multiple
 * independent consumer groups" precedent used twice already this session.
 */
@Component
public class CopiedTradePositionConsumer {

  private static final Logger log = LoggerFactory.getLogger(CopiedTradePositionConsumer.class);
  private static final String TOPIC = "copied-trades";
  private static final String DEFAULT_GROUP_ID = "core-app-positions-copied-trades";

  private final IdempotentConsumer<CopiedTradeEvent> consumer;
  private final Thread consumerThread;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CopiedTradePositionConsumer(
      Environment env,
      UnifiedJedis redisClient,
      BrokerConnectionWebSocketHandler webSocketHandler) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    String groupId = env.getProperty("POSITIONS_COPIED_TRADES_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

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
            // CopiedTradeNotificationConsumer (TICKET-115) reads this exact same topic with the
            // exact same bare-eventId key shape; without a per-consumer prefix the two race for the
            // same Redis slot and whichever polls first silently causes the other to skip a real
            // event as a false "duplicate" (caught via a flaky
            // CopiedTradeNotificationConsumerIntegrationTest failure once this consumer landed).
            .keyExtractor(event -> DEFAULT_GROUP_ID + ":" + event.getEnvelope().getEventId())
            .handler(event -> handle(event, webSocketHandler))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "positions-copied-trades-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(CopiedTradeEvent event, BrokerConnectionWebSocketHandler webSocketHandler) {
    CopiedTradeUpdateMessage message =
        new CopiedTradeUpdateMessage(
            "copy-relationships",
            "trade_update",
            event.getCopyRelationshipId(),
            event.getEventType().name(),
            event.getBrokerPositionId(),
            event.hasSymbol() ? event.getSymbol().getCanonicalCode() : null,
            event.hasVolumeLots() ? event.getVolumeLots() : null,
            event.hasRejectReason() ? event.getRejectReason() : null);
    String json = objectMapper.writeValueAsString(message);
    webSocketHandler.publishCopyRelationshipUpdate(event.getCopyRelationshipId(), json);
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
    log.info("positions: copied-trades consumer stopped");
  }

  private record CopiedTradeUpdateMessage(
      String channel,
      String type,
      String copyRelationshipId,
      String eventType,
      String brokerPositionId,
      String symbol,
      Double volumeLots,
      String rejectReason) {}
}
