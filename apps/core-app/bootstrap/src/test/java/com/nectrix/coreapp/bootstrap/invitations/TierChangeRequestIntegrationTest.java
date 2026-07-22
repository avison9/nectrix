package com.nectrix.coreapp.bootstrap.invitations;

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
 * TICKET-122 — end-to-end verification of the submit/approve/reject flow, all five acceptance
 * criteria, and the RBAC split (ADMIN+SUPER_ADMIN can approve/reject, SUPPORT cannot — the first
 * real authorization check ever written against SUPER_ADMIN, see
 * AdminController#approveTierChangeRequest's own Javadoc). Same real-Postgres-via-docker-compose,
 * real-HTTP-request-against-a-real-JWT style as RbacIntegrationTest.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TierChangeRequestIntegrationTest {

  private static final String PASSWORD = "correct horse battery staple";

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

  /** For endpoints whose body is a JSON array (e.g. the admin list), not an object. */
  private int statusOnly(String method, String path, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + path))
              .method(method, HttpRequest.BodyPublishers.noBody());
      if (bearerToken != null) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createTestUser(String email) {
    return userProvisioningApi.createUser(email, PASSWORD, "Test User", null, null, null, "US");
  }

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
        request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  /** The raw decoded JWT payload JSON, e.g. to check {@code "roles":[...]} contains a role. */
  private String decodedPayload(String jwt) {
    String[] parts = jwt.split("\\.");
    return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
  }

  @Test
  void ac1_individualUser_canSubmitAndSeeOwnPendingStatus() {
    String email = "tcr-ac1-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    grantRole(userId, "USER");
    String token = accessTokenFor(email);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", true),
            token);
    assertThat(submit.status()).isEqualTo(201);
    assertThat(submit.body().get("status")).isEqualTo("PENDING");
    assertThat(submit.body().get("target_role")).isEqualTo("MASTER");

    HttpResult mine = request("GET", "/api/v1/account/tier-change-requests/me", null, token);
    assertThat(mine.status()).isEqualTo(200);
    assertThat(mine.body().get("status")).isEqualTo("PENDING");
  }

  @Test
  void ac2_userWithExistingMasterOrFollowerRole_cannotSubmit() {
    String email = "tcr-ac2-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    grantRole(userId, "FOLLOWER");
    String token = accessTokenFor(email);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", true),
            token);
    assertThat(submit.status()).isEqualTo(409);
    assertThat(submit.body().get("error")).isEqualTo("already_master_or_follower");
  }

  @Test
  void secondSubmission_whilePending_isRejected() {
    String email = "tcr-pending-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    grantRole(userId, "USER");
    String token = accessTokenFor(email);

    HttpResult first =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "FOLLOWER", "agreement_accepted", true),
            token);
    assertThat(first.status()).isEqualTo(201);

    HttpResult second =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", true),
            token);
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().get("error")).isEqualTo("pending_request_exists");
  }

  @Test
  void ac5_agreementNotAccepted_blocksSubmission_serverSide() {
    String email = "tcr-noagree-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    grantRole(userId, "USER");
    String token = accessTokenFor(email);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", false),
            token);
    assertThat(submit.status()).isEqualTo(400);
    assertThat(submit.body().get("error")).isEqualTo("agreement_not_accepted");
  }

  @Test
  void ac3_adminApproving_grantsRole_andFreshLoginReflectsIt() {
    String email = "tcr-ac3-" + UUID.randomUUID() + "@example.com";
    String adminEmail = "tcr-ac3admin-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID adminId = createTestUser(adminEmail);
    grantRole(userId, "USER");
    grantRole(adminId, "ADMIN");
    String token = accessTokenFor(email);
    String adminToken = accessTokenFor(adminEmail);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", true),
            token);
    String requestId = (String) submit.body().get("id");

    HttpResult approve =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/approve",
            Map.of("reason", "looks good"),
            adminToken);
    assertThat(approve.status()).isEqualTo(200);
    assertThat(approve.body().get("status")).isEqualTo("APPROVED");

    // AC3's own wording: the JWT reflects it on next login/refresh, not the current session —
    // issueNewSession re-reads roles from the DB every time (JwtService/AuthService), so a fresh
    // login is the real proof, not just the DB row.
    String freshToken = accessTokenFor(email);
    assertThat(decodedPayload(freshToken)).contains("\"MASTER\"");
  }

  @Test
  void ac4_rejectedRequest_leavesRolesUnchanged_andShowsReasonToUser() {
    String email = "tcr-ac4-" + UUID.randomUUID() + "@example.com";
    String adminEmail = "tcr-ac4admin-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID adminId = createTestUser(adminEmail);
    grantRole(userId, "USER");
    grantRole(adminId, "ADMIN");
    String token = accessTokenFor(email);
    String adminToken = accessTokenFor(adminEmail);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "FOLLOWER", "agreement_accepted", true),
            token);
    String requestId = (String) submit.body().get("id");

    HttpResult reject =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/reject",
            Map.of("reason", "insufficient track record"),
            adminToken);
    assertThat(reject.status()).isEqualTo(200);
    assertThat(reject.body().get("status")).isEqualTo("REJECTED");
    assertThat(reject.body().get("review_reason")).isEqualTo("insufficient track record");

    HttpResult mine = request("GET", "/api/v1/account/tier-change-requests/me", null, token);
    assertThat(mine.body().get("status")).isEqualTo("REJECTED");
    assertThat(mine.body().get("review_reason")).isEqualTo("insufficient track record");

    String freshToken = accessTokenFor(email);
    String payload = decodedPayload(freshToken);
    assertThat(payload).doesNotContain("\"FOLLOWER\"").doesNotContain("\"MASTER\"");
  }

  @Test
  void supportCannotApprove_butAdminAndSuperAdminCan() {
    String email = "tcr-rbac-" + UUID.randomUUID() + "@example.com";
    String supportEmail = "tcr-rbacsupport-" + UUID.randomUUID() + "@example.com";
    String superAdminEmail = "tcr-rbacsuper-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID supportId = createTestUser(supportEmail);
    UUID superAdminId = createTestUser(superAdminEmail);
    grantRole(userId, "USER");
    grantRole(supportId, "SUPPORT");
    grantRole(superAdminId, "SUPER_ADMIN");
    String token = accessTokenFor(email);
    String supportToken = accessTokenFor(supportEmail);
    String superAdminToken = accessTokenFor(superAdminEmail);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "MASTER", "agreement_accepted", true),
            token);
    String requestId = (String) submit.body().get("id");

    HttpResult supportAttempt =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/approve",
            Map.of("reason", "n/a"),
            supportToken);
    assertThat(supportAttempt.status()).isEqualTo(403);

    // SUPPORT can still view the pending queue, just not decide it. The list endpoint's body is a
    // JSON array, not an object, so this checks status only (see statusOnly's own Javadoc).
    int supportListStatus =
        statusOnly("GET", "/api/v1/admin/tier-change-requests?status=PENDING", supportToken);
    assertThat(supportListStatus).isEqualTo(200);

    HttpResult superAdminApprove =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/approve",
            Map.of("reason", "SUPER_ADMIN approval — TICKET-114's first real check on this role"),
            superAdminToken);
    assertThat(superAdminApprove.status()).isEqualTo(200);
    assertThat(superAdminApprove.body().get("status")).isEqualTo("APPROVED");
  }

  @Test
  void approvingAlreadyDecidedRequest_isConflict() {
    String email = "tcr-double-" + UUID.randomUUID() + "@example.com";
    String adminEmail = "tcr-doubleadmin-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID adminId = createTestUser(adminEmail);
    grantRole(userId, "USER");
    grantRole(adminId, "ADMIN");
    String token = accessTokenFor(email);
    String adminToken = accessTokenFor(adminEmail);

    HttpResult submit =
        request(
            "POST",
            "/api/v1/account/tier-change-requests",
            Map.of("target_mode", "FOLLOWER", "agreement_accepted", true),
            token);
    String requestId = (String) submit.body().get("id");

    HttpResult firstApprove =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/approve",
            Map.of("reason", "ok"),
            adminToken);
    assertThat(firstApprove.status()).isEqualTo(200);

    HttpResult secondApprove =
        request(
            "POST",
            "/api/v1/admin/tier-change-requests/" + requestId + "/approve",
            Map.of("reason", "ok again"),
            adminToken);
    assertThat(secondApprove.status()).isEqualTo(409);
  }
}
