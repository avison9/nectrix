package com.nectrix.coreapp.bootstrap.notifications;

import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import com.nectrix.events.consumer.IdempotentConsumer;
import com.nectrix.events.consumer.RetryPolicy;
import com.nectrix.events.v1.BillingEvent;
import com.nectrix.events.v1.BillingEventType;
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
 * TICKET-115 — consumes {@code billing} for {@code invoice.generated} notifications (the ticket's
 * own required event) plus TICKET-120's 4 {@code FEE_REPORT_*} triggers — {@code
 * FEE_PERIOD_CLOSED}/{@code INVOICE_PAID}/{@code INVOICE_FAILED} are real {@link BillingEventType}
 * values but not in either ticket's set, ignored here. Unlike the other 3 consumers, {@link
 * BillingEvent} already carries {@code user_id} directly — no cross-module lookup needed.
 */
@Component
public class BillingNotificationConsumer {

  private static final Logger log = LoggerFactory.getLogger(BillingNotificationConsumer.class);
  private static final String TOPIC = "billing";
  private static final String DEFAULT_GROUP_ID = "core-app-notifications-billing";

  private final IdempotentConsumer<BillingEvent> consumer;
  private final Thread consumerThread;

  public BillingNotificationConsumer(
      Environment env, UnifiedJedis redisClient, NotificationDispatchService dispatchService) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    String groupId = env.getProperty("NOTIFICATIONS_BILLING_CONSUMER_GROUP_ID", DEFAULT_GROUP_ID);

    Properties consumerProps = new Properties();
    consumerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    Properties dlqProducerProps = new Properties();
    dlqProducerProps.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    IdempotentConsumer.Config<BillingEvent> config =
        new IdempotentConsumer.Config<BillingEvent>()
            .topic(TOPIC)
            .parser(BillingEvent.parser())
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
    this.consumerThread = new Thread(consumer::run, "notifications-billing-consumer");
    consumerThread.setDaemon(true);
    consumerThread.start();
  }

  private void handle(BillingEvent event, NotificationDispatchService dispatchService) {
    UUID userId;
    try {
      userId = UUID.fromString(event.getUserId());
    } catch (IllegalArgumentException e) {
      log.warn(
          "notifications: BillingEvent with unparsable user_id={}, dropping", event.getUserId());
      return;
    }
    switch (event.getEventType()) {
      case BILLING_EVENT_TYPE_INVOICE_GENERATED ->
          dispatchService.dispatch(
              userId, NotificationEventTypes.INVOICE_GENERATED, "New invoice", invoiceBody(event));
      case BILLING_EVENT_TYPE_FEE_REPORT_GENERATED ->
          dispatchService.dispatch(
              userId,
              NotificationEventTypes.FEE_REPORT_STATUS_CHANGED,
              "Fee report generated",
              "A new broker fee report has been generated and is ready to review.");
      case BILLING_EVENT_TYPE_FEE_REPORT_SENT ->
          dispatchService.dispatch(
              userId,
              NotificationEventTypes.FEE_REPORT_STATUS_CHANGED,
              "Fee report sent",
              "Your broker fee report has been marked as sent to the broker.");
      case BILLING_EVENT_TYPE_FEE_REPORT_CONFIRMED_DEDUCTED ->
          dispatchService.dispatch(
              userId,
              NotificationEventTypes.FEE_REPORT_STATUS_CHANGED,
              "Fee report deducted",
              "The broker has confirmed your fee report was deducted.");
      case BILLING_EVENT_TYPE_FEE_REPORT_CONFIRMED_PAID ->
          dispatchService.dispatch(
              userId,
              NotificationEventTypes.FEE_REPORT_STATUS_CHANGED,
              "Fee report paid",
              "The broker has confirmed your fee report was paid out.");
      default -> {
        // FEE_PERIOD_CLOSED/INVOICE_PAID/INVOICE_FAILED/UNSPECIFIED — not in either ticket's set.
      }
    }
  }

  private String invoiceBody(BillingEvent event) {
    if (event.hasAmount() && event.hasCurrency()) {
      return String.format(
          Locale.ROOT,
          "A new invoice for %.2f %s has been generated.",
          event.getAmount(),
          event.getCurrency());
    }
    return "A new invoice has been generated.";
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
    log.info("notifications: billing consumer stopped");
  }
}
