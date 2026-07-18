package com.nectrix.coreapp.bootstrap.notifications;

import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.RiskEvent;
import com.nectrix.events.v1.RiskEventSeverity;
import com.nectrix.redisclient.RedisDeduplicator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Locale;
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
 * TICKET-115 — consumes {@code risk} for {@code drawdown.threshold_breached} notifications. {@code
 * severity=FORCE_CLOSE} always force-delivers on IN_APP/PUSH regardless of the user's stored
 * preferences — the delivery-time half of the drawdown minimum-severity floor rule (see {@code
 * NotificationPreferenceService}'s own Javadoc for the write-time half).
 */
@Component
public class RiskNotificationConsumer {

  private static final Logger log = LoggerFactory.getLogger(RiskNotificationConsumer.class);
  private static final String TOPIC = "risk";
  private static final String DEFAULT_GROUP_ID = "core-app-notifications-risk";

  private final IdempotentConsumer<RiskEvent> consumer;
  private final Thread consumerThread;

  public RiskNotificationConsumer(
      Environment env, UnifiedJedis redisClient, NotificationDispatchService dispatchService) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    String groupId = env.getProperty("NOTIFICATIONS_RISK_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<RiskEvent> config =
        new IdempotentConsumer.Config<RiskEvent>()
            .topic(TOPIC)
            .parser(RiskEvent.parser())
            // Prefixed with this consumer's own DEFAULT_GROUP_ID -- consistent with every other
            // consumer in this app, see CopiedTradeNotificationConsumer's own Javadoc for why
            // (RedisDeduplicator's key is a global namespace, not scoped per consumer group).
            .keyExtractor(event -> DEFAULT_GROUP_ID + ":" + event.getEnvelope().getEventId())
            .handler(event -> handle(event, dispatchService))
            .deduplicator(new RedisDeduplicator(redisClient, Duration.ofMinutes(5)))
            .retryPolicy(RetryPolicy.defaultPolicy())
            .consumerProps(consumerProps)
            .dlqProducerProps(dlqProducerProps);

    this.consumer = IdempotentConsumer.create(config);
    this.consumerThread = new Thread(consumer::run, "notifications-risk-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(RiskEvent event, NotificationDispatchService dispatchService) {
    UUID copyRelationshipId = UUID.fromString(event.getCopyRelationshipId());
    boolean forceOverride =
        event.getSeverity() == RiskEventSeverity.RISK_EVENT_SEVERITY_FORCE_CLOSE;
    dispatchService.dispatchForCopyRelationship(
        copyRelationshipId,
        NotificationEventTypes.DRAWDOWN_THRESHOLD_BREACHED,
        title(event),
        body(event),
        forceOverride);
  }

  private String title(RiskEvent event) {
    return event.getSeverity() == RiskEventSeverity.RISK_EVENT_SEVERITY_FORCE_CLOSE
        ? "Drawdown limit breached — positions closed"
        : "Drawdown threshold breached — copying paused";
  }

  private String body(RiskEvent event) {
    return String.format(
        Locale.ROOT,
        "Drawdown reached %.2f%% (threshold %.2f%%).",
        event.getDrawdownPct(),
        event.getThresholdPct());
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
    log.info("notifications: risk consumer stopped");
  }
}
