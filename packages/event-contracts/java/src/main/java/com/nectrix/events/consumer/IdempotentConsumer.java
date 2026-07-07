package com.nectrix.events.consumer;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * TICKET-007's reusable idempotent-consumer + DLQ helper (docs/15-event-driven-architecture.md
 * §15.4/§15.6), generic over any Protobuf event type sharing one Kafka topic.
 *
 * <p>Per record: deserialize (a parse failure routes straight to the DLQ, no retry — it's
 * deterministic, retrying can't ever succeed); check-and-skip duplicates via the configured {@link
 * Deduplicator}; on a genuinely new record, invoke the handler with retry per the configured {@link
 * RetryPolicy}; on final exhaustion, publish the original, untouched bytes to {@code
 * "<topic>.dlq"} with failure-context headers, and only commit the offset once that DLQ publish is
 * confirmed. If the DLQ publish itself fails, this propagates out of {@link #run()} without
 * committing — restart naturally redelivers the record rather than silently losing it.
 *
 * <p>Manual commit only ({@code enable.auto.commit=false}, forced regardless of caller-supplied
 * properties) — auto-commit would let the poll loop advance past a record whose outcome (success,
 * DLQ, or neither) isn't yet certain.
 */
public final class IdempotentConsumer<T extends Message> implements AutoCloseable {

  private final KafkaConsumer<String, byte[]> consumer;
  private final KafkaProducer<String, byte[]> dlqProducer;
  private final String topic;
  private final String dlqTopic;
  private final Parser<T> parser;
  private final Function<T, String> keyExtractor;
  private final RecordHandler<T> handler;
  private final Deduplicator deduplicator;
  private final RetryPolicy retryPolicy;

  private volatile boolean running = true;

  private IdempotentConsumer(Config<T> config) {
    this.topic = Objects.requireNonNull(config.topic, "topic");
    this.dlqTopic = topic + ".dlq";
    this.parser = Objects.requireNonNull(config.parser, "parser");
    this.keyExtractor = Objects.requireNonNull(config.keyExtractor, "keyExtractor");
    this.handler = Objects.requireNonNull(config.handler, "handler");
    this.deduplicator = Objects.requireNonNull(config.deduplicator, "deduplicator");
    this.retryPolicy = config.retryPolicy;

    Properties consumerProps = new Properties();
    consumerProps.putAll(config.consumerProps);
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    this.consumer = new KafkaConsumer<>(consumerProps);
    consumer.subscribe(List.of(topic));

    Properties dlqProps = new Properties();
    dlqProps.putAll(config.dlqProducerProps);
    dlqProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    dlqProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    this.dlqProducer = new KafkaProducer<>(dlqProps);
  }

  public static <T extends Message> IdempotentConsumer<T> create(Config<T> config) {
    return new IdempotentConsumer<>(config);
  }

  /** Blocking poll loop — runs until {@link #stop()} is called (from another thread) or the process exits. */
  public void run() {
    while (running) {
      ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, byte[]> record : records) {
        processRecord(record);
      }
    }
  }

  public void stop() {
    running = false;
  }

  private void processRecord(ConsumerRecord<String, byte[]> record) {
    T event;
    try {
      event = parser.parseFrom(record.value());
    } catch (InvalidProtocolBufferException e) {
      routeToDlqAndCommit(record, "deserialization failed: " + e.getMessage(), 0);
      return;
    }

    String idempotencyKey = keyExtractor.apply(event);
    if (deduplicator.seenBefore(idempotencyKey)) {
      commit(record);
      return;
    }

    Exception lastFailure = null;
    int attempt = 0;
    while (attempt < retryPolicy.maxAttempts()) {
      attempt++;
      try {
        handler.handle(event);
        commit(record);
        return;
      } catch (Exception e) {
        lastFailure = e;
        if (attempt < retryPolicy.maxAttempts()) {
          if (!sleepUninterruptibly(retryPolicy.backoffFor(attempt))) {
            break; // interrupted — stop retrying, fall through to DLQ
          }
        }
      }
    }
    routeToDlqAndCommit(record, describeFailure(lastFailure), attempt);
  }

  private void routeToDlqAndCommit(ConsumerRecord<String, byte[]> record, String errorMessage, int attemptCount) {
    ProducerRecord<String, byte[]> dlqRecord =
        new ProducerRecord<>(dlqTopic, record.key(), record.value());
    dlqRecord
        .headers()
        .add("x-dlq-original-topic", record.topic().getBytes(StandardCharsets.UTF_8))
        .add("x-dlq-original-partition", String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8))
        .add("x-dlq-original-offset", String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8))
        .add("x-dlq-error-message", errorMessage.getBytes(StandardCharsets.UTF_8))
        .add("x-dlq-attempt-count", String.valueOf(attemptCount).getBytes(StandardCharsets.UTF_8));
    try {
      // Block until the DLQ broker acks. If this throws, propagate out of
      // run() WITHOUT committing — restart naturally redelivers the original
      // record rather than silently losing it.
      dlqProducer.send(dlqRecord).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while publishing to DLQ topic " + dlqTopic, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to publish to DLQ topic " + dlqTopic, e);
    }
    commit(record);
  }

  private void commit(ConsumerRecord<String, byte[]> record) {
    Map<TopicPartition, OffsetAndMetadata> offsets =
        Map.of(
            new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1));
    consumer.commitSync(offsets);
  }

  private static String describeFailure(Exception e) {
    return e == null ? "unknown failure" : e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  /** @return false if interrupted (caller should stop retrying), true if the sleep completed normally. */
  private static boolean sleepUninterruptibly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    stop();
    consumer.close();
    dlqProducer.close();
  }

  public static final class Config<T extends Message> {
    private String topic;
    private Parser<T> parser;
    private Function<T, String> keyExtractor;
    private RecordHandler<T> handler;
    private Deduplicator deduplicator;
    private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
    private Properties consumerProps = new Properties();
    private Properties dlqProducerProps = new Properties();

    public Config<T> topic(String topic) {
      this.topic = topic;
      return this;
    }

    public Config<T> parser(Parser<T> parser) {
      this.parser = parser;
      return this;
    }

    public Config<T> keyExtractor(Function<T, String> keyExtractor) {
      this.keyExtractor = keyExtractor;
      return this;
    }

    public Config<T> handler(RecordHandler<T> handler) {
      this.handler = handler;
      return this;
    }

    public Config<T> deduplicator(Deduplicator deduplicator) {
      this.deduplicator = deduplicator;
      return this;
    }

    public Config<T> retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public Config<T> consumerProps(Properties props) {
      this.consumerProps = props;
      return this;
    }

    public Config<T> dlqProducerProps(Properties props) {
      this.dlqProducerProps = props;
      return this;
    }
  }
}
