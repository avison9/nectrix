package com.nectrix.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.events.v1.BrokerConnectionEvent;
import com.nectrix.events.v1.BrokerConnectionEventType;
import com.nectrix.events.v1.EventEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.nectrix.redisclient.RedisClientConfig;
import com.nectrix.redisclient.RedisClientFactory;
import com.nectrix.redisclient.RedisDeduplicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

/**
 * TICKET-007 real, hands-on verification against a live Kafka broker (docker-compose.yml) and
 * Redis (for the dedup check) — AC1 (producer/consumer roundtrip with correct partition-key
 * ordering, demonstrated across a consumer restart) and AC2 (a handler that always throws lands
 * its message on {@code {topic}.dlq} after the configured retry count, not an infinite loop or a
 * silent drop).
 *
 * <p>Each test creates its OWN dedicated topic (deleted afterward) rather than reusing one of the
 * real catalog topics — a brand-new consumer group with {@code auto.offset.reset=earliest} reads
 * the ENTIRE topic history, not just what this test run produces, so sharing a long-lived topic
 * across repeated local runs (or with other tests) would replay every prior run's leftover
 * messages and inflate the assertions (caught by hand during development: a shared topic made
 * AC2's "always-fails" handler reprocess dozens of unrelated historical messages from earlier
 * runs, not just the one message this test cared about).
 */
@Tag("integration")
class IdempotentConsumerIntegrationTest {

  private static final String BOOTSTRAP_SERVERS =
      envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092");

  private final List<String> topicsToDelete = new java.util.concurrent.CopyOnWriteArrayList<>();

  private static String envOr(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  @AfterEach
  void cleanUpTopics() {
    if (topicsToDelete.isEmpty()) {
      return;
    }
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    try (Admin admin = Admin.create(props)) {
      admin.deleteTopics(topicsToDelete).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      // Best-effort cleanup only — a leftover test topic on the local/CI broker is harmless.
    }
  }

  private String createDedicatedTopic(int partitions) throws Exception {
    String topic = "test-idempotent-consumer-" + UUID.randomUUID();
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    try (Admin admin = Admin.create(props)) {
      admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
      admin
          .createTopics(List.of(new NewTopic(topic + ".dlq", 1, (short) 1)))
          .all()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    topicsToDelete.add(topic);
    topicsToDelete.add(topic + ".dlq");
    return topic;
  }

  @Test
  void ac1_perKeyOrderingSurvivesConsumerRestart() throws Exception {
    String topic = createDedicatedTopic(3);
    String keyA = "test-key-A";
    String keyB = "test-key-B";
    String groupId = "test-group-" + UUID.randomUUID();

    try (var producer = newProducer()) {
      EventProducer<BrokerConnectionEvent> eventProducer = new EventProducer<>(producer, topic);
      // Interleaved sends across two keys — only same-key ordering is a real guarantee.
      eventProducer.send(keyA, event(keyA, 1)).get();
      eventProducer.send(keyB, event(keyB, 1)).get();
      eventProducer.send(keyA, event(keyA, 2)).get();
      eventProducer.send(keyB, event(keyB, 2)).get();
      eventProducer.send(keyA, event(keyA, 3)).get();
    }

    List<Integer> observedForKeyA = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // First consumer instance: consumes the first 5 records (3 for key A, 2 for key B), then stops.
    runConsumerUntil(topic, groupId, keyA, observedForKeyA, 5);

    // Publish one more key-A event AFTER the first consumer stopped.
    try (var producer = newProducer()) {
      new EventProducer<BrokerConnectionEvent>(producer, topic).send(keyA, event(keyA, 4)).get();
    }

    // Second consumer instance, SAME group id — must pick up from the committed offset (only the
    // new record), not replay what the first instance already committed.
    runConsumerUntil(topic, groupId, keyA, observedForKeyA, 1);

    assertThat(observedForKeyA).containsExactly(1, 2, 3, 4);
  }

  @Test
  void ac2_handlerFailingEveryAttempt_landsOnDlqAfterConfiguredRetries_notInfiniteLoop() throws Exception {
    String topic = createDedicatedTopic(3);
    String key = "test-dlq-key";
    String groupId = "test-dlq-group-" + UUID.randomUUID();
    int maxAttempts = 3;

    try (var producer = newProducer()) {
      new EventProducer<BrokerConnectionEvent>(producer, topic).send(key, event(key, 1)).get();
    }

    AtomicInteger handlerInvocations = new AtomicInteger(0);
    try (UnifiedJedis jedis = RedisClientFactory.create(RedisClientConfig.fromEnv())) {
      IdempotentConsumer.Config<BrokerConnectionEvent> config =
          new IdempotentConsumer.Config<BrokerConnectionEvent>()
              .topic(topic)
              .parser(BrokerConnectionEvent.parser())
              // Idempotency key is the envelope's event_id — NOT brokerAccountId (the
              // partition/ordering key). Using the ordering key here would make every
              // same-key event look like a "duplicate" of the first one ever seen.
              .keyExtractor(event -> event.getEnvelope().getEventId())
              .handler(
                  event -> {
                    handlerInvocations.incrementAndGet();
                    throw new RuntimeException("always fails, for AC2's DLQ test");
                  })
              .deduplicator(new RedisDeduplicator(jedis, Duration.ofMinutes(5)))
              .retryPolicy(RetryPolicy.exponential(maxAttempts, Duration.ofMillis(10), Duration.ofMillis(50)))
              .consumerProps(consumerProps(groupId))
              .dlqProducerProps(producerProps());

      try (var consumer = IdempotentConsumer.<BrokerConnectionEvent>create(config)) {
        Thread consumerThread = new Thread(consumer::run);
        consumerThread.start();
        // Wait for the handler to have been invoked maxAttempts times (initial consumer-group
        // join/rebalance can itself take several seconds, observed empirically in this
        // environment — a short fixed sleep isn't reliable).
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (handlerInvocations.get() < maxAttempts && System.currentTimeMillis() < deadline) {
          Thread.sleep(100);
        }
        // Give the final DLQ-publish + commit a moment to complete after the last attempt.
        Thread.sleep(500);
        consumer.stop();
        consumerThread.join(Duration.ofSeconds(5).toMillis());
      }
    }

    assertThat(handlerInvocations.get()).isEqualTo(maxAttempts);

    // Confirm the message landed on the DLQ topic with the expected headers — proves it wasn't
    // silently dropped, and the offset advancing (no infinite retry loop) is implied by the fact
    // the consumer thread returned within the sleep window above instead of hanging.
    var dlqRecord = consumeSingleDlqRecord(topic + ".dlq", key);
    assertThat(dlqRecord).isNotNull();
    assertThat(headerValue(dlqRecord, "x-dlq-original-topic")).isEqualTo(topic);
    assertThat(headerValue(dlqRecord, "x-dlq-attempt-count")).isEqualTo(String.valueOf(maxAttempts));
    assertThat(headerValue(dlqRecord, "x-dlq-error-message")).contains("always fails, for AC2's DLQ test");
  }

  private void runConsumerUntil(
      String topic, String groupId, String filterKey, List<Integer> observed, int recordsToConsume)
      throws Exception {
    try (UnifiedJedis jedis = RedisClientFactory.create(RedisClientConfig.fromEnv())) {
      AtomicInteger consumedCount = new AtomicInteger(0);
      IdempotentConsumer.Config<BrokerConnectionEvent> config =
          new IdempotentConsumer.Config<BrokerConnectionEvent>()
              .topic(topic)
              .parser(BrokerConnectionEvent.parser())
              .keyExtractor(event -> event.getEnvelope().getEventId())
              .handler(
                  event -> {
                    if (event.getBrokerAccountId().equals(filterKey)) {
                      observed.add(Integer.parseInt(event.getDetail().substring("seq:".length())));
                    }
                    consumedCount.incrementAndGet();
                  })
              .deduplicator(new RedisDeduplicator(jedis, Duration.ofMinutes(5)))
              .consumerProps(consumerProps(groupId))
              .dlqProducerProps(producerProps());

      try (var consumer = IdempotentConsumer.<BrokerConnectionEvent>create(config)) {
        Thread consumerThread = new Thread(consumer::run);
        consumerThread.start();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (consumedCount.get() < recordsToConsume && System.currentTimeMillis() < deadline) {
          Thread.sleep(100);
        }
        consumer.stop();
        consumerThread.join(Duration.ofSeconds(5).toMillis());
      }
    }
  }

  private BrokerConnectionEvent event(String brokerAccountId, int sequence) {
    return BrokerConnectionEvent.newBuilder()
        .setEnvelope(
            EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(java.time.Instant.now().toString())
                .setSchemaVersion("v1")
                .build())
        .setBrokerAccountId(brokerAccountId)
        .setEventType(BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED)
        .setDetail("seq:" + sequence)
        .build();
  }

  private KafkaProducer<String, byte[]> newProducer() {
    return new KafkaProducer<>(producerProps());
  }

  private static Properties producerProps() {
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    return props;
  }

  private static Properties consumerProps(String groupId) {
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return props;
  }

  private ConsumerRecord<String, byte[]> consumeSingleDlqRecord(String dlqTopic, String expectedKey) {
    Properties props = consumerProps("test-dlq-reader-" + UUID.randomUUID());
    try (var consumer =
        new KafkaConsumer<String, byte[]>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(dlqTopic));
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
      while (System.currentTimeMillis() < deadline) {
        var records = consumer.poll(Duration.ofMillis(500));
        for (var record : records) {
          if (expectedKey.equals(record.key())) {
            return record;
          }
        }
      }
    }
    return null;
  }

  private String headerValue(ConsumerRecord<String, byte[]> record, String key) {
    var header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
