package com.nectrix.coreapp.bootstrap.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * TICKET-110 AC4 — real, hands-on verification of the {@code /ws/v1} broker-connection channel:
 * connect with a real access token, subscribe, then trigger a REAL connection-status change via the
 * existing internal endpoint (the exact path {@code BrokerAccountInternalService} already uses to
 * publish a real {@code BrokerConnectionEvent} to the real {@code broker-connection} Kafka topic,
 * docker-compose.yml infra) and confirm the frame arrives over the socket -- no polling, a genuine
 * push. Proven at the protocol level (a WS test client, not a browser) -- a manual browser
 * click-through (revoke cTrader access, watch a UI badge flip) is the honest, flagged
 * manual-verification step, same category as every prior live-broker-adjacent ticket.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerConnectionWebSocketIntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
    // A dedicated, brand-new consumer group id for this test class specifically -- see
    // BrokerConnectionEventConsumer's own Javadoc/comment for why sharing the default group id
    // across this suite's many @SpringBootTest contexts is a real cross-test partition-ownership
    // race, not just a timing flake. This also forces Spring to boot a SEPARATE context for this
    // class (its property set now differs from every sibling test class), so this test's own
    // BrokerConnectionWebSocketHandler bean instance is guaranteed to be the one actually wired
    // to the consumer that ends up owning the topic's partitions.
    registry.add(
        "BROKER_CONNECTION_WS_CONSUMER_GROUP_ID", () -> "test-ws-consumer-" + UUID.randomUUID());
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private String loginNewUser(String email) {
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
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
    var encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, false, 'USD', ?, ?, 'PENDING')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private void triggerRealConnectionStatusChange(UUID accountId, String status, String detail) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://localhost:"
                          + port
                          + "/internal/broker-accounts/"
                          + accountId
                          + "/connection-status"))
              .header("X-Internal-Service-Token", TEST_INTERNAL_SERVICE_TOKEN)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(Map.of("status", status, "detail", detail))))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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

  @Test
  void subscribeThenRealConnectionStatusChange_isPushedOverTheSocketWithoutPolling()
      throws Exception {
    String email = "ws-broker-conn-" + UUID.randomUUID() + "@example.com";
    String accessToken = loginNewUser(email);
    UUID userId =
        jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    UUID accountId = insertBrokerAccount(userId);

    CapturingListener listener = new CapturingListener();
    WebSocket webSocket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:" + port + "/ws/v1?access_token=" + accessToken),
                listener)
            .get(10, TimeUnit.SECONDS);
    try {
      String subscribeFrame =
          objectMapper.writeValueAsString(
              Map.of(
                  "action",
                  "subscribe",
                  "channel",
                  "broker-connection",
                  "brokerAccountId",
                  accountId.toString()));
      webSocket.sendText(subscribeFrame, true).get(5, TimeUnit.SECONDS);

      // Give the subscribe frame a moment to be processed server-side before triggering the event
      // -- a real race the test must avoid, not a flake to paper over: sending the frame and
      // publishing the event as literally back-to-back calls the server might interleave in
      // either order.
      Thread.sleep(1000);

      // A wall-clock deadline (not a fixed attempt count) absorbs real Kafka consumer-group
      // rebalance/join delays -- observed in CI (slower, resource-constrained runners) to
      // occasionally exceed 30+ seconds, well past what's typical on a local dev machine. Each
      // connection-status call publishes a genuinely new event (fresh event_id), so every retry
      // is a real, distinct trigger, not a resend the dedup layer would just swallow. 90s total
      // comfortably exceeds Kafka's own session.timeout.ms (45s) for this consumer group, the
      // slowest realistic single rebalance round trip.
      String message = null;
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(90).toMillis();
      while (message == null && System.currentTimeMillis() < deadline) {
        triggerRealConnectionStatusChange(accountId, "CONNECTED", "ws integration test");
        try {
          message = listener.firstRealMessage.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          // retry until the deadline
        }
      }
      assertThat(message).as("broker-connection WS push never arrived after retries").isNotNull();
      Map<?, ?> parsed = objectMapper.readValue(message, Map.class);
      assertThat(parsed.get("brokerAccountId")).isEqualTo(accountId.toString());
      assertThat(parsed.get("eventType")).isEqualTo("BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED");
      assertThat(parsed.get("detail")).isEqualTo("ws integration test");
    } finally {
      webSocket.abort();
    }
  }

  @Test
  void connect_withoutAccessToken_isClosed() throws Exception {
    CapturingListener listener = new CapturingListener();
    CompletableFuture<WebSocket> connected =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1"), listener)
            .toCompletableFuture();
    WebSocket webSocket = connected.get(10, TimeUnit.SECONDS);
    // The handshake itself succeeds (this is a plain WebSocketHandler, not a gated HTTP upgrade) --
    // the server closes the session immediately afterward from afterConnectionEstablished, which
    // the client observes as the socket being (or promptly becoming) not-open.
    Thread.sleep(1000);
    assertThat(webSocket.isOutputClosed() || webSocket.isInputClosed()).isTrue();
  }
}
