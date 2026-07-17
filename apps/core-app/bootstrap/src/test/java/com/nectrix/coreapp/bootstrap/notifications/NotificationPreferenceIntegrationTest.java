package com.nectrix.coreapp.bootstrap.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-115 — {@code GET/PUT /notification-preferences}, real HTTP round trips (same style as
 * {@code MasterProfileIntegrationTest}), including AC3's drawdown minimum-severity floor rejection.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationPreferenceIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Object body) {}

  private HttpResult request(String method, String path, Object body, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
      if (body != null) {
        builder
            .header("Content-Type", "application/json")
            .method(
                method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
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

  private String loginAs(String email) {
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    return (String) ((Map<?, ?>) login.body()).get("access_token");
  }

  private String newUserToken() {
    String email = "notif-prefs-" + UUID.randomUUID() + "@example.com";
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
    return loginAs(email);
  }

  @Test
  void get_withNoExplicitRows_returnsEmptyList() {
    String token = newUserToken();

    HttpResult response = request("GET", "/api/v1/notification-preferences", null, token);

    assertThat(response.status()).isEqualTo(200);
    assertThat((java.util.List<?>) response.body()).isEmpty();
  }

  @Test
  void put_disablingAnOrdinaryChannel_succeeds() {
    String token = newUserToken();

    HttpResult response =
        request(
            "PUT",
            "/api/v1/notification-preferences",
            Map.of("event_type", "copied_trade.opened", "channel", "EMAIL", "enabled", false),
            token);

    assertThat(response.status()).isEqualTo(204);

    HttpResult listed = request("GET", "/api/v1/notification-preferences", null, token);
    java.util.List<?> prefs = (java.util.List<?>) listed.body();
    assertThat(prefs).hasSize(1);
    Map<?, ?> pref = (Map<?, ?>) prefs.get(0);
    assertThat(pref.get("event_type")).isEqualTo("copied_trade.opened");
    assertThat(pref.get("channel")).isEqualTo("EMAIL");
    assertThat(pref.get("enabled")).isEqualTo(false);
  }

  @Test
  void put_disablingDrawdownInAppChannel_isRejected() {
    String token = newUserToken();

    HttpResult response =
        request(
            "PUT",
            "/api/v1/notification-preferences",
            Map.of(
                "event_type", "drawdown.threshold_breached", "channel", "IN_APP", "enabled", false),
            token);

    assertThat(response.status()).isEqualTo(400);
    assertThat(((Map<?, ?>) response.body()).get("error"))
        .isEqualTo("drawdown_notification_floor_violation");
  }

  @Test
  void put_disablingDrawdownPushChannel_stillAllowed() {
    String token = newUserToken();

    HttpResult response =
        request(
            "PUT",
            "/api/v1/notification-preferences",
            Map.of(
                "event_type", "drawdown.threshold_breached", "channel", "PUSH", "enabled", false),
            token);

    // Only IN_APP is the non-negotiable floor -- PUSH/EMAIL for the same event type remain
    // ordinary, user-adjustable preferences.
    assertThat(response.status()).isEqualTo(204);
  }

  @Test
  void put_invalidEventType_isRejected() {
    String token = newUserToken();

    HttpResult response =
        request(
            "PUT",
            "/api/v1/notification-preferences",
            Map.of("event_type", "not.a.real.event", "channel", "PUSH", "enabled", false),
            token);

    assertThat(response.status()).isEqualTo(400);
  }
}
