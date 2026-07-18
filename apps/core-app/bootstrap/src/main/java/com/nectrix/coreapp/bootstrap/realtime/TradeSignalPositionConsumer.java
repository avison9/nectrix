package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.NormalizedTradeEvent;
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
 * TICKET-116 — docs/14-api-specification.md §14.11's {@code positions.{brokerAccountId}} channel,
 * master side: consumes {@code trade-signals} (the same topic {@code apps/broker-adapters}/{@code
 * apps/mt5-bridge-gateway}'s own {@code tradesignals} package publishes to, packaged as {@link
 * NormalizedTradeEvent} — "the event a master's BrokerAdapter emits", per that proto's own comment)
 * and fans each one out to WS subscribers of that master's own {@code brokerAccountId}, keyed the
 * same way the topic itself is partitioned. Own, independent consumer group on this topic — the
 * Copy Engine (Go) is the only other reader, and Kafka topics support any number of independent
 * consumer groups (same precedent {@code CopiedTradeNotificationConsumer} already established for
 * {@code copied-trades}).
 *
 * <p>{@link NormalizedTradeEvent}, unlike {@code CopiedTradeEvent}, carries no {@code
 * EventEnvelope} — {@code event_id} is a plain top-level field on the message itself (see the
 * proto's own comment: "unique, used as idempotency source"), so the key extractor reads it
 * directly rather than through {@code .getEnvelope()}.
 */
@Component
public class TradeSignalPositionConsumer {

  private static final Logger log = LoggerFactory.getLogger(TradeSignalPositionConsumer.class);
  private static final String TOPIC = "trade-signals";
  private static final String DEFAULT_GROUP_ID = "core-app-positions-trade-signals";

  private final IdempotentConsumer<NormalizedTradeEvent> consumer;
  private final Thread consumerThread;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TradeSignalPositionConsumer(
      Environment env,
      UnifiedJedis redisClient,
      BrokerConnectionWebSocketHandler webSocketHandler) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    // Overridable so parallel @SpringBootTest contexts each get their own consumer group — same
    // isolation reasoning as every other consumer in this app (see
    // BrokerConnectionEventConsumer's identical comment).
    String groupId = env.getProperty("POSITIONS_TRADE_SIGNALS_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<NormalizedTradeEvent> config =
        new IdempotentConsumer.Config<NormalizedTradeEvent>()
            .topic(TOPIC)
            .parser(NormalizedTradeEvent.parser())
            .keyExtractor(NormalizedTradeEvent::getEventId)
            .handler(event -> handle(event, webSocketHandler))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "positions-trade-signals-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(
      NormalizedTradeEvent event, BrokerConnectionWebSocketHandler webSocketHandler) {
    var position = event.getPosition();
    var symbol = position.getSymbol();
    PositionUpdateMessage message =
        new PositionUpdateMessage(
            "positions",
            event.getMasterBrokerAccountId(),
            event.getEventType().name(),
            new PositionPayload(
                position.getBrokerPositionId(),
                symbol.getCanonicalCode(),
                position.getDirection().name(),
                position.getVolumeLots(),
                position.getOpenPrice(),
                position.hasCurrentSlPrice() ? position.getCurrentSlPrice() : null,
                position.hasCurrentTpPrice() ? position.getCurrentTpPrice() : null,
                position.getOpenedAt()),
            event.hasClosedVolumeLots() ? event.getClosedVolumeLots() : null,
            event.hasFillPrice() ? event.getFillPrice() : null,
            event.getServerTimestamp());
    String json = objectMapper.writeValueAsString(message);
    webSocketHandler.publishPositionUpdate(event.getMasterBrokerAccountId(), json);
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
    log.info("positions: trade-signals consumer stopped");
  }

  private record PositionUpdateMessage(
      String channel,
      String brokerAccountId,
      String eventType,
      PositionPayload position,
      Double closedVolumeLots,
      Double fillPrice,
      String serverTimestamp) {}

  private record PositionPayload(
      String brokerPositionId,
      String symbol,
      String direction,
      double volumeLots,
      double openPrice,
      Double currentSlPrice,
      Double currentTpPrice,
      String openedAt) {}
}
