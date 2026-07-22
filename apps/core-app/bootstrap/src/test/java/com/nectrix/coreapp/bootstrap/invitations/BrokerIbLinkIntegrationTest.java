package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * TICKET-119 — real, hands-on proof of the Master-scoped Broker IB Link CRUD endpoints, same style
 * as {@code InvitationIntegrationTest}: create/list/deactivate against a real DB, plus the object-
 * level authorization AC (a Master cannot view or manage another Master's links).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerIbLinkIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Map<String, Object> body) {}

  private record ListHttpResult(int status, List<Map<String, Object>> body) {}

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
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ListHttpResult requestList(String path, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
      if (bearerToken != null) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      List<Map<String, Object>> parsedBody =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("[")
              ? List.of()
              : objectMapper.readValue(response.body(), List.class);
      return new ListHttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void grantRole(UUID userId, String roleName) {
    jdbcTemplate.update(
        "INSERT INTO user_roles (user_id, role_id) SELECT ?, r.id FROM roles r WHERE r.name = ?",
        userId,
        roleName);
  }

  private String loginAs(String email) {
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  private record NewUser(UUID userId, String accessToken) {}

  private UUID insertBrokerAccount(UUID userId) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Original Label', false, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private NewUser newMaster() {
    String email = "ib-link-master-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    grantRole(userId, "MASTER");
    UUID brokerAccountId = insertBrokerAccount(userId);
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles
          (id, user_id, primary_broker_account_id, display_name, performance_fee_percent, fee_collection_method)
        VALUES (?, ?, ?, 'Test Master', 20.00, 'STRIPE_INVOICE')
        """,
        UUID.randomUUID(),
        userId,
        brokerAccountId);
    return new NewUser(userId, loginAs(email));
  }

  // The app-wide spring.jackson.property-naming-strategy: SNAKE_CASE (application.yml) applies to
  // this raw HTTP request/response body too — plain-Java Map.of() keys here must be the real wire
  // shape, not the Java record's own camelCase field names.
  private Map<String, Object> createRequestBody() {
    return Map.of(
        "broker_type", "CTRADER",
        "broker_display_name", "IC Markets",
        "ib_referral_url_or_code", "https://icmarkets.com/ref/abc123");
  }

  @Test
  void create_thenList_returnsTheNewLink() {
    NewUser master = newMaster();

    HttpResult created =
        request(
            "POST", "/api/v1/master/broker-ib-links", createRequestBody(), master.accessToken());
    assertThat(created.status()).isEqualTo(200);
    assertThat(created.body().get("broker_display_name")).isEqualTo("IC Markets");
    assertThat(created.body().get("is_active")).isEqualTo(true);
    String linkId = (String) created.body().get("id");

    ListHttpResult list = requestList("/api/v1/master/broker-ib-links", master.accessToken());
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body()).anySatisfy(l -> assertThat(l.get("id")).isEqualTo(linkId));
  }

  @Test
  void deactivate_removesItFromActiveOnly_notFromTheMastersOwnFullList() {
    NewUser master = newMaster();
    HttpResult created =
        request(
            "POST", "/api/v1/master/broker-ib-links", createRequestBody(), master.accessToken());
    String linkId = (String) created.body().get("id");

    HttpResult deactivate =
        request(
            "POST",
            "/api/v1/master/broker-ib-links/" + linkId + "/deactivate",
            Map.of(),
            master.accessToken());
    assertThat(deactivate.status()).isEqualTo(204);

    // AC — the Master's own management list still shows it (historical accuracy), just not active.
    ListHttpResult mine = requestList("/api/v1/master/broker-ib-links", master.accessToken());
    assertThat(mine.body())
        .anySatisfy(
            l -> {
              assertThat(l.get("id")).isEqualTo(linkId);
              assertThat(l.get("is_active")).isEqualTo(false);
            });

    // AC — no longer selectable for new invitations/account-opening (the public active-only read).
    Map<String, Object> masterProfileRow =
        jdbcTemplate.queryForMap(
            "SELECT id FROM master_profiles WHERE user_id = ?", master.userId());
    String masterProfileId = masterProfileRow.get("id").toString();
    ListHttpResult activeOnly =
        requestList("/api/v1/broker-accounts/ib-links?masterProfileId=" + masterProfileId, null);
    assertThat(activeOnly.body()).noneSatisfy(l -> assertThat(l.get("id")).isEqualTo(linkId));
  }

  @Test
  void anotherMaster_cannotSeeOrDeactivate_thisMastersLinks() {
    NewUser masterA = newMaster();
    NewUser masterB = newMaster();
    HttpResult created =
        request(
            "POST", "/api/v1/master/broker-ib-links", createRequestBody(), masterA.accessToken());
    String linkId = (String) created.body().get("id");

    ListHttpResult bList = requestList("/api/v1/master/broker-ib-links", masterB.accessToken());
    assertThat(bList.body()).noneSatisfy(l -> assertThat(l.get("id")).isEqualTo(linkId));

    HttpResult deactivateAttempt =
        request(
            "POST",
            "/api/v1/master/broker-ib-links/" + linkId + "/deactivate",
            Map.of(),
            masterB.accessToken());
    assertThat(deactivateAttempt.status()).isEqualTo(404);

    // Confirm it's genuinely untouched — masterA still sees it active.
    ListHttpResult aList = requestList("/api/v1/master/broker-ib-links", masterA.accessToken());
    assertThat(aList.body())
        .anySatisfy(
            l -> {
              assertThat(l.get("id")).isEqualTo(linkId);
              assertThat(l.get("is_active")).isEqualTo(true);
            });
  }
}
