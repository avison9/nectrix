package com.nectrix.coreapp.admin.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * TICKET-117 — System Health's Kafka consumer-lag card, the one metric with zero prior scaffolding
 * anywhere in the repo (no {@code AdminClient} usage existed before this). Real, per-consumer-group
 * lag: committed offset (via {@link AdminClient#listConsumerGroupOffsets}) vs. each
 * topic-partition's real log-end offset (via {@link AdminClient#listOffsets}), for the known,
 * hardcoded consumer-group set — the 8 real groups in {@code bootstrap} plus copy-engine's own
 * {@code "copy-engine"} group on {@code trade-signals} (see {@code copy-engine/main.go}'s {@code
 * tradeSignalsConsumerGroup} constant). All of these run with {@code auto.offset.reset=latest} (see
 * each bootstrap consumer's own {@code consumerProps}), so a partition with no committed offset yet
 * is reported as zero lag, not the full topic depth — an uncommitted-but-latest-reset consumer
 * hasn't actually fallen behind anything.
 */
@Service
public class KafkaConsumerLagService {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerLagService.class);

  /**
   * group id -> topic. Mirrors every bootstrap consumer's own {@code DEFAULT_GROUP_ID}/{@code
   * TOPIC}.
   */
  private static final Map<String, String> KNOWN_CONSUMER_GROUPS =
      Map.ofEntries(
          Map.entry("core-app-notifications-billing", "billing"),
          Map.entry("core-app-notifications-risk", "risk"),
          Map.entry("core-app-notifications-broker-connection", "broker-connection"),
          Map.entry("core-app-notifications-copied-trades", "copied-trades"),
          Map.entry("core-app-positions-copied-trades", "copied-trades"),
          Map.entry("core-app-broker-connection-ws", "broker-connection"),
          Map.entry("core-app-positions-trade-signals", "trade-signals"),
          Map.entry("core-app-admin-reconciliation-drift", "reconciliation"),
          Map.entry("copy-engine", "trade-signals"));

  private final AdminClient adminClient;

  public KafkaConsumerLagService(Environment env) {
    String bootstrapServers =
        env.getProperty("KAFKA_HOST", "localhost") + ":" + env.getProperty("KAFKA_PORT", "9092");
    Properties props = new Properties();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    this.adminClient = AdminClient.create(props);
  }

  /**
   * {@code messagesReceived} is topic-wide (every partition's real log-end minus real log-start
   * offset, via {@code OffsetSpec.earliest()}/{@code latest()}) — independent of any one consumer
   * group, so it stays accurate even for a group that's never run. {@code messagesProcessed} is
   * this group's own committed offset minus each partition's earliest offset, summed — "how many
   * of the messages this topic has ever received has this group actually consumed." Both are -1 on
   * a failure to compute, same sentinel {@code lag} already used.
   */
  public record ConsumerGroupLag(
      String groupId, String topic, long lag, long messagesReceived, long messagesProcessed) {}

  public List<ConsumerGroupLag> currentLag() {
    return KNOWN_CONSUMER_GROUPS.entrySet().stream()
        .map(e -> lagFor(e.getKey(), e.getValue()))
        .toList();
  }

  private ConsumerGroupLag lagFor(String groupId, String topic) {
    try {
      List<TopicPartitionInfo> partitionInfos =
          adminClient
              .describeTopics(List.of(topic))
              .allTopicNames()
              .get(10, TimeUnit.SECONDS)
              .get(topic)
              .partitions();
      List<TopicPartition> partitions =
          partitionInfos.stream().map(p -> new TopicPartition(topic, p.partition())).toList();

      Map<TopicPartition, OffsetSpec> earliestSpecs =
          partitions.stream()
              .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));
      Map<TopicPartition, OffsetSpec> latestSpecs =
          partitions.stream()
              .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
      ListOffsetsResult earliestOffsetsResult = adminClient.listOffsets(earliestSpecs);
      ListOffsetsResult latestOffsetsResult = adminClient.listOffsets(latestSpecs);

      Map<TopicPartition, Long> earliestOffsets = new java.util.HashMap<>();
      Map<TopicPartition, Long> latestOffsets = new java.util.HashMap<>();
      long messagesReceived = 0;
      for (TopicPartition partition : partitions) {
        long earliest =
            earliestOffsetsResult.partitionResult(partition).get(10, TimeUnit.SECONDS).offset();
        long latest =
            latestOffsetsResult.partitionResult(partition).get(10, TimeUnit.SECONDS).offset();
        earliestOffsets.put(partition, earliest);
        latestOffsets.put(partition, latest);
        messagesReceived += Math.max(0, latest - earliest);
      }

      Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
          adminClient
              .listConsumerGroupOffsets(groupId)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS);

      long totalLag = 0;
      long messagesProcessed = 0;
      for (TopicPartition partition : partitions) {
        org.apache.kafka.clients.consumer.OffsetAndMetadata committedMeta =
            committed.get(partition);
        long committedOffset = committedMeta == null ? -1 : committedMeta.offset();
        if (committedOffset >= 0) {
          totalLag += Math.max(0, latestOffsets.get(partition) - committedOffset);
          messagesProcessed += Math.max(0, committedOffset - earliestOffsets.get(partition));
        }
        // committedOffset < 0 (never committed) -- auto.offset.reset=latest means this
        // consumer starts at the tail, not behind; contributes zero lag/processed (see class
        // Javadoc).
      }
      return new ConsumerGroupLag(groupId, topic, totalLag, messagesReceived, messagesProcessed);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ConsumerGroupLag(groupId, topic, -1, -1, -1);
    } catch (ExecutionException | TimeoutException e) {
      log.warn("admin: failed to compute consumer lag for group={}", groupId, e);
      return new ConsumerGroupLag(groupId, topic, -1, -1, -1);
    }
  }

  @PreDestroy
  public void shutdown() {
    adminClient.close(Duration.ofSeconds(5));
  }
}
