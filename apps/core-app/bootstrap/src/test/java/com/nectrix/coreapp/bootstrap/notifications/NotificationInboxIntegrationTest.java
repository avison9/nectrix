package com.nectrix.coreapp.bootstrap.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-115 — {@code GET/POST /notifications*}, the {@code notification_log} {@code channel=
 * 'IN_APP'}-rows-as-inbox design (024-notification-log-read-state.sql). Rows are seeded directly
 * via SQL (same "insert the row the real dispatch path would have produced" precedent {@code
 * SettlementIntegrationTest}'s own fixture helpers already established) since there's no
 * self-service way to create one over HTTP — only {@code NotificationDispatchService} does that.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationInboxIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Object body) {}

  private HttpResult request(String method, String path, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .method(method, HttpRequest.BodyPublishers.noBody());
      if (bearerToken != null) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      Object parsedBody =
          response.body() == null || response.body().isBlank()
              ? null
              : objectMapper.readValue(response.body(), Object.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record NewUser(UUID userId, String accessToken) {}

  private NewUser newUser() {
    String email = "notif-inbox-" + UUID.randomUUID() + "@example.com";
    UUID userId =
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
      return new NewUser(userId, (String) body.get("access_token"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID insertInAppLogRow(UUID userId, String eventType) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notification_log (user_id, event_type, channel, payload, status)
        VALUES (?, ?, 'IN_APP', '{}'::jsonb, 'SENT')
        RETURNING id
        """,
        UUID.class,
        userId,
        eventType);
  }

  @Test
  void list_unreadOnly_excludesAlreadyReadRows() {
    NewUser user = newUser();
    UUID unread = insertInAppLogRow(user.userId(), "copied_trade.opened");
    UUID read = insertInAppLogRow(user.userId(), "copied_trade.closed");
    jdbcTemplate.update("UPDATE notification_log SET read_at = now() WHERE id = ?", read);

    HttpResult response = request("GET", "/api/v1/notifications?unread=true", user.accessToken());

    assertThat(response.status()).isEqualTo(200);
    List<?> body = (List<?>) response.body();
    assertThat(body).hasSize(1);
    assertThat(((Map<?, ?>) body.get(0)).get("id")).isEqualTo(unread.toString());
  }

  @Test
  void markRead_ownRow_succeedsAndPersists() {
    NewUser user = newUser();
    UUID logId = insertInAppLogRow(user.userId(), "copied_trade.opened");

    HttpResult response =
        request("POST", "/api/v1/notifications/" + logId + "/read", user.accessToken());

    assertThat(response.status()).isEqualTo(204);
    var readAt =
        jdbcTemplate.queryForObject(
            "SELECT read_at FROM notification_log WHERE id = ?", java.sql.Timestamp.class, logId);
    assertThat(readAt).isNotNull();
  }

  @Test
  void markRead_anotherUsersRow_isNotFound() {
    NewUser owner = newUser();
    NewUser other = newUser();
    UUID logId = insertInAppLogRow(owner.userId(), "copied_trade.opened");

    HttpResult response =
        request("POST", "/api/v1/notifications/" + logId + "/read", other.accessToken());

    assertThat(response.status()).isEqualTo(404);
  }
}
