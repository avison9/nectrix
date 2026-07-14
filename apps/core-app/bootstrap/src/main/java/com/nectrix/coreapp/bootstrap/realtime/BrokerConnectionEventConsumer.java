package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.BrokerConnectionEvent;
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
 * TICKET-110 — core-app's first real Kafka CONSUMER (everything before this, {@code
 * BrokerConnectionEventProducerConfiguration}, is producer-only). Reads the same {@code
 * broker-connection} topic that producer publishes to, and fans each decoded event out to {@link
 * BrokerConnectionWebSocketHandler}'s subscribed sessions — no polling from the browser, a real
 * push the moment {@code BrokerAccountInternalService} records a connection-status change.
 *
 * <p>Reuses {@link IdempotentConsumer} (TICKET-007's shared idempotent-consumer + DLQ helper)
 * rather than a raw {@code KafkaConsumer} loop — same dedup/retry/DLQ discipline every other
 * consumer of this topic gets, for free. A poll-loop failure here is a lost live UI update, not a
 * financial-correctness issue, so this consumer's own group id is independent of any other consumer
 * of this topic (each maintains its own offset).
 *
 * <p>Builds the WS JSON payload with its own plain {@code ObjectMapper}, not the app-wide autowired
 * bean — see {@link BrokerConnectionWebSocketHandler}'s Javadoc for why the WS wire dialect is
 * deliberately camelCase, not the REST API's SNAKE_CASE-configured shared bean.
 */
@Component
public class BrokerConnectionEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(BrokerConnectionEventConsumer.class);
  private static final String TOPIC = "broker-connection";
  private static final String DEFAULT_GROUP_ID = "core-app-broker-connection-ws";

  private final IdempotentConsumer<BrokerConnectionEvent> consumer;
  private final Thread consumerThread;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public BrokerConnectionEventConsumer(
      Environment env,
      UnifiedJedis redisClient,
      BrokerConnectionWebSocketHandler webSocketHandler) {
    String host = env.getProperty("KAFKA_HOST", "localhost");
    String port = env.getProperty("KAFKA_PORT", "9092");
    String bootstrapServers = host + ":" + port;
    // Overridable so integration tests can each use their own dedicated, brand-new consumer
    // group -- multiple @SpringBootTest contexts across this test module otherwise contend for
    // the SAME group id's partition ownership as contexts start/stop across the suite (Kafka's
    // consumer-group protocol assigns each partition exclusively to one live member at a time),
    // a real cross-test-isolation race, not a timing flake a longer wait can paper over. Real
    // deployments never set this env var, so production always uses the one stable group id.
    String groupId = env.getProperty("BROKER_CONNECTION_WS_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

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
            .handler(event -> handle(event, webSocketHandler))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "broker-connection-ws-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(
      BrokerConnectionEvent event, BrokerConnectionWebSocketHandler webSocketHandler) {
    BrokerConnectionMessage message =
        new BrokerConnectionMessage(
            "broker-connection",
            event.getBrokerAccountId(),
            event.getEventType().name(),
            event.hasDetail() ? event.getDetail() : null);
    String json = objectMapper.writeValueAsString(message);
    webSocketHandler.publish(event.getBrokerAccountId(), json);
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
    log.info("realtime: broker-connection WS consumer stopped");
  }

  private record BrokerConnectionMessage(
      String channel, String brokerAccountId, String eventType, String detail) {}
}
