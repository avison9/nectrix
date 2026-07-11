package com.nectrix.coreapp.bootstrap.invitations;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-103 — real, hands-on verification of the symbol-mapping confirmation flow
 * (docs/14-api-specification.md §14.3): auto-suggestion via the internal endpoint (the same shape
 * apps/broker-adapters/apps/mt5-bridge-gateway's coreappclient packages POST), confirm/override via
 * the public PUT, ownership enforcement (mirrors RbacIntegrationTest's ac2 pattern exactly), and
 * the 404-vs-403 distinction. Auto-suggestion correctness against a REAL broker's own symbol list
 * is not exercised here — see this ticket's own honest-limitation note (same discipline as
 * TICKET-101/102).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SymbolMappingIntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private record HttpResult(int status, Map<String, Object> body) {}

  private HttpResult request(
      String method, String path, Map<String, Object> body, String bearerToken) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path));
      if (bearerToken != null) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      if (body != null) {
        builder.header("Content-Type", "application/json");
        builder.method(
            method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, Object> parsedBody =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record ListResult(int status, List<Map<String, Object>> body) {}

  /**
   * GET .../symbol-mappings returns a top-level JSON array, not an object — a separate helper from
   * {@link #request}, whose HttpResult assumes a JSON object body.
   */
  private ListResult requestList(String path, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET();
      if (bearerToken != null) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      List<Map<String, Object>> parsedBody =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("[")
              ? List.of()
              : objectMapper.readValue(response.body(), List.class);
      return new ListResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The internal suggestions POST — camelCase body, X-Internal-Service-Token, no bearer token. */
  private HttpResult internalSuggest(UUID brokerAccountId, List<Map<String, Object>> suggestions) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      baseUrl()
                          + "/internal/broker-accounts/"
                          + brokerAccountId
                          + "/symbol-mappings/suggestions"))
              .header("X-Internal-Service-Token", TEST_INTERNAL_SERVICE_TOKEN)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(Map.of("suggestions", suggestions))))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return new HttpResult(response.statusCode(), Map.of());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createTestUser(String email) {
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  /** Mirrors RbacIntegrationTest's own identical helper — direct SQL, no HTTP round trip. */
  private void grantRole(UUID userId, String roleName) {
    jdbcTemplate.update(
        """
        INSERT INTO user_roles (user_id, role_id)
        SELECT ?, r.id FROM roles r WHERE r.name = ?
        ON CONFLICT (user_id, role_id) DO NOTHING
        """,
        userId,
        roleName);
  }

  private String accessTokenFor(String email) {
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  /** Real signup + real MT5 link, exactly like BrokerAccountMtIntegrationTest's own setup. */
  private UUID linkMt5Account(String bearerToken, String login) {
    HttpResult link =
        request(
            "POST",
            "/api/v1/broker-accounts/mt5",
            Map.of(
                "login", login,
                "password", "terminal-password-123",
                "server", "Pepperstone-Demo",
                "is_demo", true,
                "display_label", "My MT5 Demo"),
            bearerToken);
    assertThat(link.status()).isEqualTo(200);
    return UUID.fromString((String) link.body().get("id"));
  }

  private Map<String, Object> suggestion(String canonicalSymbol, String brokerSymbolName) {
    return Map.of(
        "canonicalSymbol", canonicalSymbol,
        "brokerSymbolName", brokerSymbolName,
        "contractSize", 100000.0,
        "lotStep", 0.01,
        "minLot", 0.01,
        "maxLot", 50.0,
        "pipSize", 0.0001,
        "digits", 5,
        "marginCurrency", "USD");
  }

  @Test
  void list_withoutBearerToken_isRejected() {
    HttpResult response =
        request(
            "GET", "/api/v1/broker-accounts/" + UUID.randomUUID() + "/symbol-mappings", null, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void confirm_withoutBearerToken_isRejected() {
    HttpResult response =
        request(
            "PUT",
            "/api/v1/broker-accounts/" + UUID.randomUUID() + "/symbol-mappings/EURUSD",
            Map.of("broker_symbol_name", "EURUSD.a"),
            null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void list_forAnotherUsersAccount_isForbidden_andBypassesForStaff() {
    String emailA = "symmap-a-" + UUID.randomUUID() + "@example.com";
    String emailB = "symmap-b-" + UUID.randomUUID() + "@example.com";
    UUID userA = createTestUser(emailA);
    UUID userB = createTestUser(emailB);
    grantRole(userA, "FOLLOWER");
    grantRole(userB, "FOLLOWER");
    String tokenA = accessTokenFor(emailA);
    String tokenB = accessTokenFor(emailB);

    UUID accountB = linkMt5Account(tokenB, "600001");

    // User A's own token must not reach User B's broker account's mappings.
    HttpResult forbidden =
        request("GET", "/api/v1/broker-accounts/" + accountB + "/symbol-mappings", null, tokenA);
    assertThat(forbidden.status()).isEqualTo(403);

    // A nonexistent broker account id is a plain 404, not conflated with the ownership check.
    HttpResult notFound =
        request(
            "GET",
            "/api/v1/broker-accounts/" + UUID.randomUUID() + "/symbol-mappings",
            null,
            tokenA);
    assertThat(notFound.status()).isEqualTo(404);

    // An ADMIN bypasses ownership entirely (RbacIntegrationTest's ac2 — staff can view any
    // BrokerAccount's resources, including its symbol mappings).
    String emailAdmin = "symmap-admin-" + UUID.randomUUID() + "@example.com";
    UUID admin = createTestUser(emailAdmin);
    grantRole(admin, "ADMIN");
    String adminToken = accessTokenFor(emailAdmin);
    ListResult adminView =
        requestList("/api/v1/broker-accounts/" + accountB + "/symbol-mappings", adminToken);
    assertThat(adminView.status()).isEqualTo(200);
  }

  @Test
  void confirm_withNoSuggestionYet_returns404() {
    String email = "symmap-nosuggest-" + UUID.randomUUID() + "@example.com";
    UUID user = createTestUser(email);
    grantRole(user, "FOLLOWER");
    String token = accessTokenFor(email);
    UUID accountId = linkMt5Account(token, "600101");

    HttpResult response =
        request(
            "PUT",
            "/api/v1/broker-accounts/" + accountId + "/symbol-mappings/EURUSD",
            Map.of("broker_symbol_name", "EURUSD.a"),
            token);
    assertThat(response.status()).isEqualTo(404);
    assertThat(response.body().get("error")).isEqualTo("symbol_mapping_not_found");
  }

  @Test
  void suggestThenList_showsAnUnconfirmedSuggestion_thenConfirm_marksItConfirmed() {
    String email = "symmap-confirm-" + UUID.randomUUID() + "@example.com";
    UUID user = createTestUser(email);
    grantRole(user, "FOLLOWER");
    String token = accessTokenFor(email);
    UUID accountId = linkMt5Account(token, "600201");

    HttpResult suggestResponse =
        internalSuggest(accountId, List.of(suggestion("EURUSD", "EURUSD.a")));
    assertThat(suggestResponse.status()).isEqualTo(200);

    ListResult listAfterSuggest =
        requestList("/api/v1/broker-accounts/" + accountId + "/symbol-mappings", token);
    assertThat(listAfterSuggest.status()).isEqualTo(200);
    assertThat(listAfterSuggest.body()).hasSize(1);
    Map<String, Object> suggested = listAfterSuggest.body().get(0);
    assertThat(suggested.get("canonical_symbol")).isEqualTo("EURUSD");
    assertThat(suggested.get("broker_symbol_name")).isEqualTo("EURUSD.a");
    assertThat(suggested.get("is_confirmed")).isEqualTo(false);

    HttpResult confirmResponse =
        request(
            "PUT",
            "/api/v1/broker-accounts/" + accountId + "/symbol-mappings/EURUSD",
            Map.of("broker_symbol_name", "EURUSD.a"),
            token);
    assertThat(confirmResponse.status()).isEqualTo(200);
    assertThat(confirmResponse.body().get("canonical_symbol")).isEqualTo("EURUSD");
    assertThat(confirmResponse.body().get("broker_symbol_name")).isEqualTo("EURUSD.a");
    assertThat(confirmResponse.body().get("is_confirmed")).isEqualTo(true);

    Boolean confirmedInDb =
        jdbcTemplate.queryForObject(
            "SELECT is_confirmed FROM symbol_mappings WHERE broker_account_id = ? AND canonical_symbol = 'EURUSD'",
            Boolean.class,
            accountId);
    assertThat(confirmedInDb).isTrue();
  }

  @Test
  void confirm_canOverrideTheAutoSuggestedBrokerSymbolName() {
    String email = "symmap-override-" + UUID.randomUUID() + "@example.com";
    UUID user = createTestUser(email);
    grantRole(user, "FOLLOWER");
    String token = accessTokenFor(email);
    UUID accountId = linkMt5Account(token, "600301");

    HttpResult suggestResponse =
        internalSuggest(accountId, List.of(suggestion("EURUSD", "EURUSD.a")));
    assertThat(suggestResponse.status()).isEqualTo(200);

    // User overrides the auto-suggested "EURUSD.a" with a different real broker symbol name.
    HttpResult confirmResponse =
        request(
            "PUT",
            "/api/v1/broker-accounts/" + accountId + "/symbol-mappings/EURUSD",
            Map.of("broker_symbol_name", "EURUSDpro"),
            token);
    assertThat(confirmResponse.status()).isEqualTo(200);
    assertThat(confirmResponse.body().get("broker_symbol_name")).isEqualTo("EURUSDpro");
  }

  @Test
  void suggestTwice_neverClobbersAnAlreadyConfirmedMapping() {
    String email = "symmap-noclobber-" + UUID.randomUUID() + "@example.com";
    UUID user = createTestUser(email);
    grantRole(user, "FOLLOWER");
    String token = accessTokenFor(email);
    UUID accountId = linkMt5Account(token, "600401");

    internalSuggest(accountId, List.of(suggestion("EURUSD", "EURUSD.a")));
    request(
        "PUT",
        "/api/v1/broker-accounts/" + accountId + "/symbol-mappings/EURUSD",
        Map.of("broker_symbol_name", "EURUSD.a"),
        token);

    // A second auto-suggestion round (e.g. the EA reconnecting) with DIFFERENT spec numbers must
    // not un-confirm or overwrite the row the user already confirmed.
    internalSuggest(accountId, List.of(suggestion("EURUSD", "EURUSD.b")));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT broker_symbol_name, is_confirmed FROM symbol_mappings WHERE broker_account_id = ? AND canonical_symbol = 'EURUSD'",
            accountId);
    assertThat(row.get("broker_symbol_name")).isEqualTo("EURUSD.a");
    assertThat(row.get("is_confirmed")).isEqualTo(true);
  }
}
