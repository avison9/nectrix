package com.nectrix.coreapp.bootstrap.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.events.v1.AssetClass;
import com.nectrix.events.v1.CopiedTradeEvent;
import com.nectrix.events.v1.CopiedTradeEventType;
import com.nectrix.events.v1.EventEnvelope;
import com.nectrix.events.v1.NormalizedPosition;
import com.nectrix.events.v1.NormalizedSymbol;
import com.nectrix.events.v1.NormalizedTradeEvent;
import com.nectrix.events.v1.TradeDirection;
import com.nectrix.events.v1.TradeEventType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-116 — docs/14-api-specification.md §14.11's {@code positions.{brokerAccountId}}/{@code
 * copy-relationships.{id}} WS channels, proven end-to-end: a genuine {@link NormalizedTradeEvent}
 * on the real {@code trade-signals} topic (the exact wire format apps/broker-adapters/
 * apps/mt5-bridge-gateway's own producers use) reaches a subscribed positions socket via {@link
 * TradeSignalPositionConsumer}; a genuine {@link CopiedTradeEvent} on {@code copied-trades} reaches
 * a subscribed copy-relationships socket via {@link CopiedTradePositionConsumer}; and a real {@code
 * pause} REST call reaches that same channel via {@code CopyRelationshipService}'s own synchronous,
 * same-request push (no Kafka round trip for that one). Same real-Kafka-publish-plus-real-WS-client
 * style as CopiedTradeNotificationConsumerIntegrationTest/
 * BrokerConnectionWebSocketIntegrationTest.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PositionChannelIntegrationTest {

  @DynamicPropertySource
  static void consumerGroupIds(DynamicPropertyRegistry registry) {
    // Dedicated, brand-new consumer groups for this test class — see
    // BrokerConnectionEventConsumer's own Javadoc for why sharing default group ids across this
    // suite's many @SpringBootTest contexts is a real cross-test partition-ownership race.
    registry.add(
        "POSITIONS_TRADE_SIGNALS_CONSUMER_GROUP_ID",
        () -> "test-positions-trade-signals-" + UUID.randomUUID());
    registry.add(
        "POSITIONS_COPIED_TRADES_CONSUMER_GROUP_ID",
        () -> "test-positions-copied-trades-" + UUID.randomUUID());
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private KafkaProducer<String, byte[]> producer;

  private KafkaProducer<String, byte[]> producer() {
    if (producer == null) {
      String host = System.getenv().getOrDefault("KAFKA_HOST", "localhost");
      String kafkaPort = System.getenv().getOrDefault("KAFKA_PORT", "9092");
      Properties props = new Properties();
      props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, host + ":" + kafkaPort);
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

  private String loginNewUser(String email) {
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
    return loginAs(email);
  }

  /** Login only, no user creation — for a user {@link #insertCopyRelationship} already created. */
  private String loginAs(String email) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(
                          Map.of("email", email, "password", "correct horse battery staple"))))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
      return (String) body.get("access_token");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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

  private record Chain(UUID copyRelationshipId, String followerAccessToken) {}

  private Chain insertCopyRelationship(UUID masterUserId, UUID masterBrokerAccountId) {
    String followerEmail = "position-ws-follower-" + UUID.randomUUID() + "@example.com";
    UUID followerUserId =
        userProvisioningApi.createUser(
            followerEmail, "correct horse battery staple", "Test User", null, null, null, "US");
    String followerAccessToken = loginAs(followerEmail);
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID masterProfileId = insertMasterProfile(masterUserId, masterBrokerAccountId);
    UUID mmProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        mmProfileId);
    UUID riskProfileId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", riskProfileId);
    UUID invitationId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?)
        """,
        invitationId,
        masterProfileId,
        "invitee-" + invitationId + "@example.com",
        "token-hash-" + invitationId,
        masterUserId,
        java.sql.Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);
    return new Chain(id, followerAccessToken);
  }

  private void publishTradeSignal(UUID masterBrokerAccountId, String eventId) {
    NormalizedTradeEvent event =
        NormalizedTradeEvent.newBuilder()
            .setEventId(eventId)
            .setMasterBrokerAccountId(masterBrokerAccountId.toString())
            .setEventType(TradeEventType.TRADE_EVENT_TYPE_POSITION_OPENED)
            .setPosition(
                NormalizedPosition.newBuilder()
                    .setBrokerPositionId("pos-" + eventId)
                    .setSymbol(
                        NormalizedSymbol.newBuilder()
                            .setCanonicalCode("EURUSD")
                            .setAssetClass(AssetClass.ASSET_CLASS_FX)
                            .build())
                    .setDirection(TradeDirection.TRADE_DIRECTION_BUY)
                    .setVolumeLots(1.0)
                    .setOpenPrice(1.1)
                    .setOpenedAt(Instant.now().toString())
                    .build())
            .setServerTimestamp(Instant.now().toString())
            .setReceivedAtGateway(Instant.now().toString())
            .build();
    producer()
        .send(
            new ProducerRecord<>(
                "trade-signals", masterBrokerAccountId.toString(), event.toByteArray()));
    producer().flush();
  }

  private void publishCopiedTradeOpened(UUID copyRelationshipId, String eventId) {
    CopiedTradeEvent event =
        CopiedTradeEvent.newBuilder()
            .setEnvelope(
                EventEnvelope.newBuilder()
                    .setEventId(eventId)
                    .setOccurredAt(Instant.now().toString())
                    .setSchemaVersion("v1")
                    .build())
            .setCopyRelationshipId(copyRelationshipId.toString())
            .setEventType(CopiedTradeEventType.COPIED_TRADE_EVENT_TYPE_OPENED)
            .setBrokerPositionId("pos-" + eventId)
            .setSymbol(
                NormalizedSymbol.newBuilder()
                    .setCanonicalCode("EURUSD")
                    .setAssetClass(AssetClass.ASSET_CLASS_FX)
                    .build())
            .setVolumeLots(1.0)
            .build();
    producer()
        .send(
            new ProducerRecord<>(
                "copied-trades", copyRelationshipId.toString(), event.toByteArray()));
    producer().flush();
  }

  private static class CapturingListener implements WebSocket.Listener {
    final CompletableFuture<String> firstRealMessage = new CompletableFuture<>();
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      buffer.append(data);
      if (last) {
        String message = buffer.toString();
        buffer.setLength(0);
        if (!firstRealMessage.isDone()) {
          firstRealMessage.complete(message);
        }
      }
      webSocket.request(1);
      return null;
    }
  }

  private WebSocket connect(String accessToken, CapturingListener listener) throws Exception {
    return HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .buildAsync(
            URI.create("ws://localhost:" + port + "/ws/v1?access_token=" + accessToken), listener)
        .get(10, TimeUnit.SECONDS);
  }

  @Test
  void realTradeSignalEvent_isPushedToASubscribedPositionsSocket() throws Exception {
    String masterEmail = "position-ws-master-" + UUID.randomUUID() + "@example.com";
    String masterAccessToken = loginNewUser(masterEmail);
    UUID masterUserId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, masterEmail);
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);

    CapturingListener listener = new CapturingListener();
    WebSocket webSocket = connect(masterAccessToken, listener);
    try {
      String subscribeFrame =
          objectMapper.writeValueAsString(
              Map.of(
                  "action",
                  "subscribe",
                  "channel",
                  "positions",
                  "brokerAccountId",
                  masterBrokerAccountId.toString()));
      webSocket.sendText(subscribeFrame, true).get(5, TimeUnit.SECONDS);
      Thread.sleep(1000);

      // Widened from 90s to 180s -- the suite has grown to a couple dozen @SpringBootTest classes
      // each joining/leaving their own consumer group against the same shared CI broker; see
      // CopiedTradeNotificationConsumerIntegrationTest's own identical widening, hit the same way.
      String message = null;
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(180).toMillis();
      while (message == null && System.currentTimeMillis() < deadline) {
        publishTradeSignal(masterBrokerAccountId, UUID.randomUUID().toString());
        try {
          message = listener.firstRealMessage.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          // retry until the deadline
        }
      }
      assertThat(message).as("positions WS push never arrived after retries").isNotNull();
      Map<?, ?> parsed = objectMapper.readValue(message, Map.class);
      assertThat(parsed.get("channel")).isEqualTo("positions");
      assertThat(parsed.get("brokerAccountId")).isEqualTo(masterBrokerAccountId.toString());
      assertThat(parsed.get("eventType")).isEqualTo("TRADE_EVENT_TYPE_POSITION_OPENED");
    } finally {
      webSocket.abort();
    }
  }

  @Test
  void realCopiedTradeEvent_isPushedToASubscribedCopyRelationshipSocket() throws Exception {
    String masterEmail = "position-ws-master2-" + UUID.randomUUID() + "@example.com";
    String masterAccessToken = loginNewUser(masterEmail);
    UUID masterUserId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, masterEmail);
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    Chain chain = insertCopyRelationship(masterUserId, masterBrokerAccountId);

    CapturingListener listener = new CapturingListener();
    WebSocket webSocket = connect(chain.followerAccessToken(), listener);
    try {
      String subscribeFrame =
          objectMapper.writeValueAsString(
              Map.of(
                  "action",
                  "subscribe",
                  "channel",
                  "copy-relationships",
                  "id",
                  chain.copyRelationshipId().toString()));
      webSocket.sendText(subscribeFrame, true).get(5, TimeUnit.SECONDS);
      Thread.sleep(1000);

      // Widened from 90s to 180s -- the suite has grown to a couple dozen @SpringBootTest classes
      // each joining/leaving their own consumer group against the same shared CI broker; see
      // CopiedTradeNotificationConsumerIntegrationTest's own identical widening, hit the same way.
      String message = null;
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(180).toMillis();
      while (message == null && System.currentTimeMillis() < deadline) {
        publishCopiedTradeOpened(chain.copyRelationshipId(), UUID.randomUUID().toString());
        try {
          message = listener.firstRealMessage.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          // retry until the deadline
        }
      }
      assertThat(message).as("copy-relationships WS push never arrived after retries").isNotNull();
      Map<?, ?> parsed = objectMapper.readValue(message, Map.class);
      assertThat(parsed.get("channel")).isEqualTo("copy-relationships");
      assertThat(parsed.get("type")).isEqualTo("trade_update");
      assertThat(parsed.get("copyRelationshipId")).isEqualTo(chain.copyRelationshipId().toString());
    } finally {
      webSocket.abort();
    }
  }

  @Test
  void realPauseCall_pushesAStatusChangedFrame_toASubscribedCopyRelationshipSocket()
      throws Exception {
    String masterEmail = "position-ws-master3-" + UUID.randomUUID() + "@example.com";
    String masterAccessToken = loginNewUser(masterEmail);
    UUID masterUserId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, masterEmail);
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    Chain chain = insertCopyRelationship(masterUserId, masterBrokerAccountId);

    CapturingListener listener = new CapturingListener();
    WebSocket webSocket = connect(chain.followerAccessToken(), listener);
    try {
      String subscribeFrame =
          objectMapper.writeValueAsString(
              Map.of(
                  "action",
                  "subscribe",
                  "channel",
                  "copy-relationships",
                  "id",
                  chain.copyRelationshipId().toString()));
      webSocket.sendText(subscribeFrame, true).get(5, TimeUnit.SECONDS);
      Thread.sleep(1000);

      HttpRequest pauseRequest =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://localhost:"
                          + port
                          + "/api/v1/copy-relationships/"
                          + chain.copyRelationshipId()
                          + "/pause"))
              .header("Authorization", "Bearer " + chain.followerAccessToken())
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> pauseResponse =
          httpClient.send(pauseRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(pauseResponse.statusCode()).isEqualTo(200);

      String message = listener.firstRealMessage.get(15, TimeUnit.SECONDS);
      Map<?, ?> parsed = objectMapper.readValue(message, Map.class);
      assertThat(parsed.get("channel")).isEqualTo("copy-relationships");
      assertThat(parsed.get("type")).isEqualTo("status_changed");
      assertThat(parsed.get("status")).isEqualTo("PAUSED");
    } finally {
      webSocket.abort();
    }
  }
}
