package com.nectrix.coreapp.bootstrap.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * #421 — real end-to-end coverage of the admin manual follower-master link endpoint, following
 * {@code Ticket117IntegrationTest}/{@code FollowerAnalyticsIntegrationTest}'s own established
 * real-HTTP-round-trip style.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminCopyLinkIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private static final String PASSWORD = "correct horse battery staple";

  private record HttpResult(int status, Map<String, Object> body) {}

  private HttpResult request(String method, String path, Map<String, Object> body, String token) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }
      builder.header("Content-Type", "application/json");
      builder.method(
          method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
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

  private UUID createUser(String email) {
    return userProvisioningApi.createUser(email, PASSWORD, "Test User", null, null, null, "US");
  }

  private void grantRole(UUID userId, String roleName) {
    jdbcTemplate.update(
        "INSERT INTO user_roles (user_id, role_id) SELECT ?, r.id FROM roles r WHERE r.name = ?",
        userId,
        roleName);
  }

  private String accessTokenFor(String email) {
    HttpResult result =
        request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
    assertThat(result.status()).isEqualTo(200);
    return (String) result.body().get("access_token");
  }

  private UUID insertBrokerAccount(UUID userId) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Test Label', false, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private record MasterFixture(String email, UUID userId, UUID primaryBrokerAccountId) {}

  private MasterFixture createMaster(String feeCollectionMethod) {
    String email = "admin-link-master-" + UUID.randomUUID() + "@example.com";
    UUID userId = createUser(email);
    grantRole(userId, "MASTER");
    UUID brokerAccountId = insertBrokerAccount(userId);
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles
          (id, user_id, primary_broker_account_id, display_name, fee_collection_method)
        VALUES (?, ?, ?, 'Test Master', ?)
        """,
        masterProfileId,
        userId,
        brokerAccountId,
        feeCollectionMethod);
    return new MasterFixture(email, userId, brokerAccountId);
  }

  private String createAdminToken() {
    UUID adminId = createUser("admin-link-admin-" + UUID.randomUUID() + "@example.com");
    grantRole(adminId, "ADMIN");
    return accessTokenFor(
        jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, adminId));
  }

  @Test
  void stripeInvoiceMaster_linksStraightToActive() {
    MasterFixture master = createMaster("STRIPE_INVOICE");
    String followerEmail = "admin-link-follower-" + UUID.randomUUID() + "@example.com";
    UUID followerId = createUser(followerEmail);
    grantRole(followerId, "FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(followerId);
    String adminToken = createAdminToken();

    HttpResult result =
        request(
            "POST",
            "/api/v1/admin/users/" + followerId + "/copy-relationships",
            Map.of(
                "master_broker_account_id",
                master.primaryBrokerAccountId().toString(),
                "follower_broker_account_id",
                followerBrokerAccountId.toString()),
            adminToken);

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().get("status")).isEqualTo("ACTIVE");
    UUID copyRelationshipId = UUID.fromString((String) result.body().get("copy_relationship_id"));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT originating_admin_action, master_broker_account_id, follower_broker_account_id "
                + "FROM copy_relationships WHERE id = ?",
            copyRelationshipId);
    assertThat(row.get("originating_admin_action")).isEqualTo(true);
    assertThat(row.get("master_broker_account_id").toString())
        .isEqualTo(master.primaryBrokerAccountId().toString());
    assertThat(row.get("follower_broker_account_id").toString())
        .isEqualTo(followerBrokerAccountId.toString());
  }

  @Test
  void brokerPartnershipMaster_landsInPendingAgreement_notActive() {
    MasterFixture master = createMaster("BROKER_PARTNERSHIP");
    UUID followerId = createUser("admin-link-follower-" + UUID.randomUUID() + "@example.com");
    grantRole(followerId, "FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(followerId);
    String adminToken = createAdminToken();

    HttpResult result =
        request(
            "POST",
            "/api/v1/admin/users/" + followerId + "/copy-relationships",
            Map.of(
                "master_broker_account_id",
                master.primaryBrokerAccountId().toString(),
                "follower_broker_account_id",
                followerBrokerAccountId.toString()),
            adminToken);

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().get("status")).isEqualTo("PENDING_AGREEMENT");
  }

  @Test
  void unknownMasterBrokerAccountId_returns404() {
    UUID followerId = createUser("admin-link-follower-" + UUID.randomUUID() + "@example.com");
    grantRole(followerId, "FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(followerId);
    String adminToken = createAdminToken();

    HttpResult result =
        request(
            "POST",
            "/api/v1/admin/users/" + followerId + "/copy-relationships",
            Map.of(
                "master_broker_account_id",
                UUID.randomUUID().toString(),
                "follower_broker_account_id",
                followerBrokerAccountId.toString()),
            adminToken);

    assertThat(result.status()).isEqualTo(404);
  }

  /**
   * TICKET-125 — the admin-portal's own two-step flow resolves a Master's eligible broker accounts
   * by email before this endpoint is ever called; an unknown email 404s here instead.
   */
  @Test
  void findMasterBrokerAccountsByEmail_returnsTheirEligibleAccounts() {
    MasterFixture master = createMaster("STRIPE_INVOICE");
    String adminToken = createAdminToken();

    HttpResponse<String> response =
        uncheckedGet(
            "/api/v1/admin/users/by-email/master-broker-accounts?email=" + master.email(),
            adminToken);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains(master.primaryBrokerAccountId().toString());
  }

  @Test
  void findMasterBrokerAccountsByEmail_forUnknownEmail_returns404() {
    String adminToken = createAdminToken();

    HttpResponse<String> response =
        uncheckedGet(
            "/api/v1/admin/users/by-email/master-broker-accounts?email=no-such-user-"
                + UUID.randomUUID()
                + "@example.com",
            adminToken);

    assertThat(response.statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> uncheckedGet(String path, String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void brokerAccountNotOwnedByFollower_returns400() {
    MasterFixture master = createMaster("STRIPE_INVOICE");
    UUID followerId = createUser("admin-link-follower-" + UUID.randomUUID() + "@example.com");
    grantRole(followerId, "FOLLOWER");
    UUID someoneElseId = createUser("admin-link-other-" + UUID.randomUUID() + "@example.com");
    UUID notOwnedAccountId = insertBrokerAccount(someoneElseId);
    String adminToken = createAdminToken();

    HttpResult result =
        request(
            "POST",
            "/api/v1/admin/users/" + followerId + "/copy-relationships",
            Map.of(
                "master_broker_account_id",
                master.primaryBrokerAccountId().toString(),
                "follower_broker_account_id",
                notOwnedAccountId.toString()),
            adminToken);

    assertThat(result.status()).isEqualTo(400);
  }

  @Test
  void duplicateNonTerminalLink_returns409() {
    MasterFixture master = createMaster("STRIPE_INVOICE");
    UUID followerId = createUser("admin-link-follower-" + UUID.randomUUID() + "@example.com");
    grantRole(followerId, "FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(followerId);
    String adminToken = createAdminToken();

    Map<String, Object> body =
        Map.of(
            "master_broker_account_id",
            master.primaryBrokerAccountId().toString(),
            "follower_broker_account_id",
            followerBrokerAccountId.toString());
    HttpResult first =
        request(
            "POST", "/api/v1/admin/users/" + followerId + "/copy-relationships", body, adminToken);
    assertThat(first.status()).isEqualTo(200);

    HttpResult second =
        request(
            "POST", "/api/v1/admin/users/" + followerId + "/copy-relationships", body, adminToken);
    assertThat(second.status()).isEqualTo(409);
  }

  @Test
  void followerCallerWithoutAdminRole_returns403() {
    MasterFixture master = createMaster("STRIPE_INVOICE");
    UUID followerId = createUser("admin-link-follower-" + UUID.randomUUID() + "@example.com");
    grantRole(followerId, "FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(followerId);
    String followerToken =
        accessTokenFor(
            jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, followerId));

    HttpResult result =
        request(
            "POST",
            "/api/v1/admin/users/" + followerId + "/copy-relationships",
            Map.of(
                "master_broker_account_id",
                master.primaryBrokerAccountId().toString(),
                "follower_broker_account_id",
                followerBrokerAccountId.toString()),
            followerToken);

    assertThat(result.status()).isEqualTo(403);
  }
}
