package com.nectrix.coreapp.bootstrap.admin;

import com.nectrix.coreapp.admin.api.AdminEventIngestApi;
import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.ReconciliationDriftDetected;
import com.nectrix.redisclient.RedisDeduplicator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
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
 * TICKET-117 — the first real consumer of the {@code reconciliation} topic. TICKET-109's
 * copy-engine already publishes a real {@link ReconciliationDriftDetected} event
 * (copy-engine/internal/pipeline/reconcile.go) every time it detects drift, but nothing has ever
 * read it back until now — System Health's drift-rate card needs a real, persisted count, not a
 * synthetic number. Same {@link IdempotentConsumer} shape as every other bootstrap consumer (see
 * {@code BrokerConnectionEventConsumer}'s own Javadoc for the dedup/retry/DLQ reasoning), own
 * dedicated {@code DEFAULT_GROUP_ID} so this consumer maintains its own offset independent of any
 * future consumer of the same topic.
 */
@Component
public class ReconciliationDriftConsumer {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationDriftConsumer.class);
  private static final String TOPIC = "reconciliation";
  private static final String DEFAULT_GROUP_ID = "core-app-admin-reconciliation-drift";

  private final IdempotentConsumer<ReconciliationDriftDetected> consumer;
  private final Thread consumerThread;

  public ReconciliationDriftConsumer(
      Environment env, UnifiedJedis redisClient, AdminEventIngestApi adminEventIngestApi) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    String groupId =
        env.getProperty("ADMIN_RECONCILIATION_DRIFT_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<ReconciliationDriftDetected> config =
        new IdempotentConsumer.Config<ReconciliationDriftDetected>()
            .topic(TOPIC)
            .parser(ReconciliationDriftDetected.parser())
            // Prefixed with this consumer's own DEFAULT_GROUP_ID -- same reasoning as every
            // other consumer in this app (RedisDeduplicator's key is a global namespace, not
            // scoped per consumer group -- see BrokerConnectionEventConsumer's own Javadoc).
            .keyExtractor(event -> DEFAULT_GROUP_ID + ":" + event.getEnvelope().getEventId())
            .handler(event -> handle(event, adminEventIngestApi))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "admin-reconciliation-drift-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(ReconciliationDriftDetected event, AdminEventIngestApi adminEventIngestApi) {
    UUID brokerAccountId;
    try {
      brokerAccountId = UUID.fromString(event.getBrokerAccountId());
    } catch (IllegalArgumentException e) {
      log.warn(
          "admin: ReconciliationDriftDetected with unparsable broker_account_id={}, dropping",
          event.getBrokerAccountId());
      return;
    }
    adminEventIngestApi.recordReconciliationDrift(
        brokerAccountId, event.getDriftType().name(), Instant.now());
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
    log.info("admin: reconciliation-drift consumer stopped");
  }
}
