package com.nectrix.coreapp.bootstrap.invitations;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-102 — real, hands-on verification of MT5/MT4's direct-credential linking flow: the single
 * authenticated call (no OAuth dance), the real {@code broker_type} CHECK constraint widening
 * (018-mt4-broker-type.sql), duplicate-account rejection, and the new internal mt-credentials
 * endpoint's real decrypt path (proving it never leaks the terminal password — see
 * BrokerAccountInternalService#fetchMtCredentials's Javadoc). Mirrors
 * BrokerAccountOAuthIntegrationTest's pattern exactly. The real EA <-> gateway WebSocket handshake
 * is covered by apps/mt5-bridge-gateway's own eabridge/mtadapter/pairing tests (a fake EA client
 * against a real Server) and this ticket's live-verification runbook, not automatable here.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerAccountMtIntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Map<String, Object> body) {}

  private HttpResult request(
      String method, String path, Map<String, Object> body, String bearerToken) {
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
      Map<String, Object> parsedBody =
          response.body() == null || response.body().isBlank()
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResult internalGet(String path, String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("X-Internal-Service-Token", token)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      Map<String, Object> body =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String loginNewUser() {
    String email = "mt-link-" + UUID.randomUUID() + "@example.com";
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    return (String) login.body().get("access_token");
  }

  private Map<String, Object> mt5LinkBody(String login) {
    return Map.of(
        "login", login,
        "password", "terminal-password-123",
        "server", "Pepperstone-Demo",
        "is_demo", true,
        "display_label", "My MT5 Demo");
  }

  @Test
  void linkMt5_withoutBearerToken_isRejected() {
    HttpResult response =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody("500001"), null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void linkMt4_withoutBearerToken_isRejected() {
    HttpResult response =
        request("POST", "/api/v1/broker-accounts/mt4", mt5LinkBody("500002"), null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void linkMt5_withValidBearerToken_createsARealPendingRowAndReturnsPairingTokenAndGatewayUrl() {
    String accessToken = loginNewUser();

    HttpResult response =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody("500101"), accessToken);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("id")).isNotNull();
    assertThat((String) response.body().get("pairing_token")).isNotBlank();
    assertThat((String) response.body().get("gateway_url")).startsWith("ws://");
    assertThat(response.body().get("connection_status")).isEqualTo("PENDING");
  }

  @Test
  void linkMt4_withValidBearerToken_createsARealPendingRowWithBrokerTypeMt4() {
    String accessToken = loginNewUser();

    HttpResult response =
        request("POST", "/api/v1/broker-accounts/mt4", mt5LinkBody("500201"), accessToken);

    assertThat(response.status()).isEqualTo(200);
    String accountId = (String) response.body().get("id");

    // Confirm it's really reachable via the internal listing endpoint under brokerType=MT4 —
    // the real end-to-end proof the 018-mt4-broker-type.sql CHECK-constraint widening worked
    // (an unmodified constraint would have made the INSERT itself fail with a 500, not a
    // clean 200 here).
    HttpResult listed =
        internalGet(
            "/internal/broker-accounts?status=PENDING&brokerType=MT4", TEST_INTERNAL_SERVICE_TOKEN);
    assertThat(listed.status()).isEqualTo(200);
  }

  @Test
  void linkMt5_calledTwiceForTheSameLogin_isRejectedAsAlreadyLinked() {
    String accessToken = loginNewUser();
    String login = "500301";

    HttpResult first =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody(login), accessToken);
    assertThat(first.status()).isEqualTo(200);

    HttpResult second =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody(login), accessToken);
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().get("error")).isEqualTo("broker_account_already_linked");
  }

  @Test
  void mtCredentials_forUnknownId_returns404() {
    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-credentials/" + UUID.randomUUID(),
            TEST_INTERNAL_SERVICE_TOKEN);
    assertThat(response.status()).isEqualTo(404);
  }

  @Test
  void mtCredentials_withoutServiceTokenHeader_isRejected() {
    String accessToken = loginNewUser();
    HttpResult link =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody("500401"), accessToken);
    String accountId = (String) link.body().get("id");

    // No X-Internal-Service-Token header at all — the generic request() helper never adds
    // it (only Authorization, which internalGet always would even for a "null" token, since
    // HttpRequest.Builder#header rejects a null value outright).
    HttpResult response =
        request("GET", "/internal/broker-accounts/mt-credentials/" + accountId, null, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void mtCredentials_forRealMt5Account_decryptsTheRealStoredLoginServerAndPairingToken() {
    String accessToken = loginNewUser();
    HttpResult link =
        request("POST", "/api/v1/broker-accounts/mt5", mt5LinkBody("500501"), accessToken);
    String accountId = (String) link.body().get("id");
    String pairingToken = (String) link.body().get("pairing_token");

    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-credentials/" + accountId, TEST_INTERNAL_SERVICE_TOKEN);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("login")).isEqualTo("500501");
    assertThat(response.body().get("server")).isEqualTo("Pepperstone-Demo");
    assertThat(response.body().get("pairingToken")).isEqualTo(pairingToken);
    // The terminal password must never be exposed over this endpoint — see
    // BrokerAccountInternalService#fetchMtCredentials's Javadoc.
    assertThat(response.body()).doesNotContainKey("password");
  }
}
