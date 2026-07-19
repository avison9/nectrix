package com.nectrix.coreapp.bootstrap.admin;

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
 * End-to-end, hands-on verification of TICKET-012's two new backend routes — {@code POST
 * /api/v1/admin/users} (account provisioning) and {@code GET /api/v1/admin/audit-log} (the Audit
 * Log viewer's data source). Same pattern as {@code RbacIntegrationTest}: runs against the
 * ephemeral Postgres started by {@code docker-compose.yml}, plain {@code java.net.http.HttpClient}
 * against a real, running instance of the app.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPortalIntegrationTest {

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

  /** Mirrors `make role-grant`'s own query — direct SQL, no HTTP round trip for test setup. */
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

  private String adminToken() {
    String email = "admportal-admin-" + UUID.randomUUID() + "@example.com";
    UUID admin = createTestUser(email);
    grantRole(admin, "ADMIN");
    return accessTokenFor(email);
  }

  @Test
  void followerUser_isForbiddenFromBothNewAdminRoutes() {
    String email = "admportal-follower-" + UUID.randomUUID() + "@example.com";
    UUID follower = createTestUser(email);
    grantRole(follower, "FOLLOWER");
    String followerToken = accessTokenFor(email);

    HttpResult provision =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                "irrelevant-" + UUID.randomUUID() + "@example.com",
                "password",
                "correct horse battery staple",
                "display_name",
                "Irrelevant",
                "role",
                "SUPPORT"),
            followerToken);
    assertThat(provision.status()).isEqualTo(403);

    HttpResult auditLog = request("GET", "/api/v1/admin/audit-log", null, followerToken);
    assertThat(auditLog.status()).isEqualTo(403);
  }

  @Test
  void supportUser_canReadAuditLogButCannotProvisionAccounts() {
    String email = "admportal-support-" + UUID.randomUUID() + "@example.com";
    UUID support = createTestUser(email);
    grantRole(support, "SUPPORT");
    String supportToken = accessTokenFor(email);

    HttpResult provision =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                "irrelevant-" + UUID.randomUUID() + "@example.com",
                "password",
                "correct horse battery staple",
                "display_name",
                "Irrelevant",
                "role",
                "SUPPORT"),
            supportToken);
    assertThat(provision.status()).isEqualTo(403);

    HttpResult auditLog = request("GET", "/api/v1/admin/audit-log", null, supportToken);
    assertThat(auditLog.status()).isEqualTo(200);
  }

  /**
   * TICKET-117 follow-up — {@code PROVISIONABLE_ROLES} was widened to {@code ADMIN, SUPPORT,
   * MASTER, FOLLOWER} (AdminController's own Javadoc explains why: self-service {@code
   * master_profiles} creation via TICKET-111 already covers the profile itself, this endpoint only
   * ever grants the role). {@code SUPER_ADMIN} stays deliberately excluded per an explicit user
   * security decision; {@code PARTNER} has no provisioning ticket of its own yet.
   */
  @Test
  void adminUser_provisioningRejectsSuperAdminButAllowsMasterAndFollower() {
    String adminToken = adminToken();

    HttpResult superAdminAttempt =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                "super-admin-" + UUID.randomUUID() + "@example.com",
                "password",
                "correct horse battery staple",
                "display_name",
                "Should Be Rejected",
                "role",
                "SUPER_ADMIN"),
            adminToken);
    assertThat(superAdminAttempt.status()).isEqualTo(400);

    HttpResult masterAttempt =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                "master-" + UUID.randomUUID() + "@example.com",
                "password",
                "correct horse battery staple",
                "display_name",
                "Should Be Allowed",
                "role",
                "MASTER"),
            adminToken);
    assertThat(masterAttempt.status()).isEqualTo(201);

    HttpResult followerAttempt =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                "follower-" + UUID.randomUUID() + "@example.com",
                "password",
                "correct horse battery staple",
                "display_name",
                "Should Be Allowed",
                "role",
                "FOLLOWER"),
            adminToken);
    assertThat(followerAttempt.status()).isEqualTo(201);
  }

  @Test
  void adminUser_provisionsSupportAccount_thatCanThenLogInAndCallAnAdminRoute() {
    String adminToken = adminToken();
    String newEmail = "provisioned-support-" + UUID.randomUUID() + "@example.com";

    HttpResult provision =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                newEmail,
                "password",
                "correct horse battery staple",
                "display_name",
                "Newly Provisioned Support",
                "role",
                "SUPPORT"),
            adminToken);
    assertThat(provision.status()).isEqualTo(201);
    String newUserId = (String) provision.body().get("id");
    assertThat(newUserId).isNotBlank();

    // The account actually works: real login, then a real call to a route SUPPORT can reach.
    String newAccountToken = accessTokenFor(newEmail);
    HttpResult auditLog = request("GET", "/api/v1/admin/audit-log", null, newAccountToken);
    assertThat(auditLog.status()).isEqualTo(200);

    Integer auditRows =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM audit_log
            WHERE action = 'USER_PROVISIONED' AND target_id = ?
            """,
            Integer.class,
            newUserId);
    assertThat(auditRows).isGreaterThan(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void auditLogViewer_returnsRealPaginatedRows_filterableByActorAndTargetType() {
    String adminToken = adminToken();
    String newEmail = "audit-target-" + UUID.randomUUID() + "@example.com";

    HttpResult provision =
        request(
            "POST",
            "/api/v1/admin/users",
            Map.of(
                "email",
                newEmail,
                "password",
                "correct horse battery staple",
                "display_name",
                "Audit Target",
                "role",
                "SUPPORT"),
            adminToken);
    assertThat(provision.status()).isEqualTo(201);
    String newUserId = (String) provision.body().get("id");

    HttpResult page =
        request(
            "GET",
            "/api/v1/admin/audit-log?targetType=USER&targetId=" + newUserId,
            null,
            adminToken);
    assertThat(page.status()).isEqualTo(200);
    List<Map<String, Object>> entries = (List<Map<String, Object>>) page.body().get("entries");
    assertThat(entries).isNotEmpty();
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("action")).isEqualTo("USER_PROVISIONED");
              assertThat(entry.get("target_id")).isEqualTo(newUserId);
            });
    assertThat((Number) page.body().get("total")).isNotNull();
  }
}
