package com.nectrix.coreapp.bootstrap.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * End-to-end, hands-on verification of TICKET-006's ACs 1 through 3 (AC4 — the {@code make
 * role-grant}/{@code role-revoke}/{@code role-list} CLI — is verified separately by hand, not via
 * JUnit; see the ticket's own plan). Runs against the ephemeral Postgres started by {@code
 * docker-compose.yml}, same as {@code AuthIntegrationTest}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RbacIntegrationTest {

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
          response.body() == null || response.body().isBlank()
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createTestUser(String email) {
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  /**
   * Mirrors `make role-grant`'s own query — direct SQL, no HTTP round trip needed for test setup.
   */
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

  private UUID seedBrokerAccount(UUID ownerUserId) {
    String id =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO broker_accounts
              (user_id, broker_type, broker_account_login, currency, credentials_ciphertext, credentials_key_version)
            VALUES (?, 'MT5', ?, 'USD', ?, 1)
            RETURNING id::text
            """,
            String.class,
            ownerUserId,
            "login-" + UUID.randomUUID(),
            "dummy-ciphertext".getBytes(StandardCharsets.UTF_8));
    return UUID.fromString(id);
  }

  @Test
  void ac1_followerOnlyUser_isForbiddenFromAdminOnlyRoute() {
    String email = "rbac-ac1-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    grantRole(userId, "FOLLOWER");
    String followerToken = accessTokenFor(email);

    HttpResult response =
        request(
            "POST",
            "/api/v1/admin/ledger-adjustments",
            Map.of(
                "target_type",
                "INVOICE",
                "target_id",
                "irrelevant",
                "amount",
                10,
                "reason",
                "test"),
            followerToken);

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void ac2_objectOwnershipCheck_blocksOtherUsersBrokerAccount_andBypassesForStaff() {
    String emailA = "rbac-ac2a-" + UUID.randomUUID() + "@example.com";
    String emailB = "rbac-ac2b-" + UUID.randomUUID() + "@example.com";
    UUID userA = createTestUser(emailA);
    UUID userB = createTestUser(emailB);
    grantRole(userA, "FOLLOWER");
    grantRole(userB, "FOLLOWER");
    String tokenA = accessTokenFor(emailA);

    UUID accountB = seedBrokerAccount(userB);

    // User A's own token must not reach User B's broker account.
    HttpResult forbidden = request("GET", "/api/v1/broker-accounts/" + accountB, null, tokenA);
    assertThat(forbidden.status()).isEqualTo(403);

    // A nonexistent id is a plain 404, not conflated with the ownership check.
    HttpResult notFound =
        request("GET", "/api/v1/broker-accounts/" + UUID.randomUUID(), null, tokenA);
    assertThat(notFound.status()).isEqualTo(404);

    // An ADMIN bypasses ownership entirely (docs 12.3 — staff can view any BrokerAccount).
    String emailAdmin = "rbac-ac2admin-" + UUID.randomUUID() + "@example.com";
    UUID admin = createTestUser(emailAdmin);
    grantRole(admin, "ADMIN");
    String adminToken = accessTokenFor(emailAdmin);
    HttpResult adminView = request("GET", "/api/v1/broker-accounts/" + accountB, null, adminToken);
    assertThat(adminView.status()).isEqualTo(200);
  }

  @Test
  void ac3_supportCanImpersonateButNotAdjustLedger_adminCanAdjustLedger() {
    String emailSupport = "rbac-ac3support-" + UUID.randomUUID() + "@example.com";
    String emailAdmin = "rbac-ac3admin-" + UUID.randomUUID() + "@example.com";
    String emailTarget = "rbac-ac3target-" + UUID.randomUUID() + "@example.com";
    UUID support = createTestUser(emailSupport);
    UUID admin = createTestUser(emailAdmin);
    UUID target = createTestUser(emailTarget);
    grantRole(support, "SUPPORT");
    grantRole(admin, "ADMIN");
    grantRole(target, "FOLLOWER");
    String supportToken = accessTokenFor(emailSupport);
    String adminToken = accessTokenFor(emailAdmin);

    HttpResult impersonate =
        request("POST", "/api/v1/admin/impersonate/" + target, Map.of(), supportToken);
    assertThat(impersonate.status()).isEqualTo(200);
    String impersonationToken = (String) impersonate.body().get("access_token");
    assertThat(decodeClaim(impersonationToken, "sub")).isEqualTo(target.toString());
    assertThat(decodeClaim(impersonationToken, "impersonated_by")).isEqualTo(support.toString());

    Integer auditRows =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM audit_log
            WHERE action = 'IMPERSONATE_START' AND actor_user_id = ? AND target_id = ?
            """,
            Integer.class,
            support,
            target.toString());
    assertThat(auditRows).isGreaterThan(0);

    Map<String, Object> ledgerBody =
        Map.of("target_type", "INVOICE", "target_id", "irrelevant", "amount", 10, "reason", "test");
    HttpResult supportLedgerAttempt =
        request("POST", "/api/v1/admin/ledger-adjustments", ledgerBody, supportToken);
    assertThat(supportLedgerAttempt.status()).isEqualTo(403);

    HttpResult adminLedgerAttempt =
        request("POST", "/api/v1/admin/ledger-adjustments", ledgerBody, adminToken);
    assertThat(adminLedgerAttempt.status()).isEqualTo(204);
  }

  /** Decodes a JWT's payload (second segment) just enough to pull one string claim. */
  private String decodeClaim(String jwt, String field) {
    String[] parts = jwt.split("\\.");
    String payloadJson =
        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    int idx = payloadJson.indexOf("\"" + field + "\":\"");
    String rest = payloadJson.substring(idx + field.length() + 4);
    return rest.substring(0, rest.indexOf('"'));
  }
}
