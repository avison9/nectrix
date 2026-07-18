package com.nectrix.coreapp.bootstrap.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.events.v1.CopiedTradeEvent;
import com.nectrix.events.v1.CopiedTradeEventType;
import com.nectrix.events.v1.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * TICKET-115 — the real end-to-end pipeline for one of the 4 notification consumers, proven all the
 * way through: a genuine {@code CopiedTradeEvent} published onto the real {@code copied-trades}
 * Kafka topic (the exact wire format {@code apps/copy-engine}'s own producer uses — same proto,
 * same raw-bytes-value convention) is picked up by {@link CopiedTradeNotificationConsumer},
 * resolved to a real user via {@code copy_relationships.follower_user_id}, and dispatched into a
 * real {@code notification_log} row. The other 3 consumers share this exact shape (same {@code
 * IdempotentConsumer} class, same {@code NotificationDispatchService} call) — this one test is
 * strong evidence for all 4, not just this one, so it isn't duplicated 3 more times for topics that
 * differ only in field names.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CopiedTradeNotificationConsumerIntegrationTest {

  @DynamicPropertySource
  static void consumerGroupId(DynamicPropertyRegistry registry) {
    // A dedicated, brand-new consumer group for this test class -- see
    // BrokerConnectionEventConsumer's own Javadoc for why sharing the default group id across
    // this suite's many @SpringBootTest contexts is a real cross-test partition-ownership race.
    registry.add(
        "NOTIFICATIONS_COPIED_TRADES_CONSUMER_GROUP_ID",
        () -> "test-notifications-copied-trades-" + UUID.randomUUID());
  }

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KafkaProducer<String, byte[]> producer;

  private KafkaProducer<String, byte[]> producer() {
    if (producer == null) {
      String host = System.getenv().getOrDefault("KAFKA_HOST", "localhost");
      String port = System.getenv().getOrDefault("KAFKA_PORT", "9092");
      Properties props = new Properties();
      props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, host + ":" + port);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
      producer = new KafkaProducer<>(props);
    }
    return producer;
  }

  @AfterEach
  void closeProducer() {
    if (producer != null) {
      producer.close();
    }
  }

  private UUID newUser() {
    String email = "consumer-test-" + UUID.randomUUID() + "@example.com";
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  private UUID insertBrokerAccount(UUID userId) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Label', false, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private UUID insertMasterProfile(UUID masterUserId, UUID primaryBrokerAccountId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name) VALUES (?, ?, ?, 'Test Master')",
        id,
        masterUserId,
        primaryBrokerAccountId);
    return id;
  }

  private UUID insertMoneyManagementProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        id);
    return id;
  }

  private UUID insertRiskProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", id);
    return id;
  }

  private UUID insertInvitation(UUID masterProfileId, UUID createdByUserId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?)
        """,
        id,
        masterProfileId,
        "invitee-" + id + "@example.com",
        "token-hash-" + id,
        createdByUserId,
        java.sql.Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
    return id;
  }

  private record Chain(UUID copyRelationshipId, UUID followerUserId) {}

  private Chain insertCopyRelationship() {
    UUID masterUserId = newUser();
    UUID followerUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID masterProfileId = insertMasterProfile(masterUserId, masterBrokerAccountId);
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, masterUserId);

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, performance_fee_percent, fee_collection_method,
           originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 20.00, 'STRIPE_INVOICE', ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);
    return new Chain(id, followerUserId);
  }

  private void publishCopiedTradeFailed(UUID copyRelationshipId, String eventId) {
    CopiedTradeEvent event =
        CopiedTradeEvent.newBuilder()
            .setEnvelope(
                EventEnvelope.newBuilder()
                    .setEventId(eventId)
                    .setOccurredAt(Instant.now().toString())
                    .setSchemaVersion("v1")
                    .build())
            .setCopyRelationshipId(copyRelationshipId.toString())
            .setEventType(CopiedTradeEventType.COPIED_TRADE_EVENT_TYPE_FAILED)
            .setRejectReason("UNMAPPED_SYMBOL")
            .build();
    producer()
        .send(
            new ProducerRecord<>(
                "copied-trades", copyRelationshipId.toString(), event.toByteArray()));
    producer().flush();
  }

  @Test
  void realCopiedTradeFailedEvent_producesARealNotificationLogRow() {
    Chain chain = insertCopyRelationship();
    String eventId = UUID.randomUUID().toString();

    long deadline = System.currentTimeMillis() + Duration.ofSeconds(90).toMillis();
    java.util.List<Map<String, Object>> rows = java.util.List.of();
    while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
      publishCopiedTradeFailed(chain.copyRelationshipId(), eventId);
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      rows =
          jdbcTemplate.queryForList(
              "SELECT * FROM notification_log WHERE user_id = ? AND event_type = 'copied_trade.failed'",
              chain.followerUserId());
    }

    assertThat(rows).as("no notification_log row appeared after retries").isNotEmpty();
    assertThat(rows).anySatisfy(row -> assertThat(row.get("channel")).isEqualTo("IN_APP"));
  }
}
