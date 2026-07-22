package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * TICKET-110 — real, hands-on verification of the new list/PATCH/DELETE endpoints
 * BrokerAccountController gains, the object-level (IDOR) ownership gate on snapshot/positions, and
 * openedViaIbLinkId's end-to-end round trip through the MT5 linking flow (BrokerIbLinkController's
 * own new read endpoint too). The live network hop snapshot/positions ultimately make to a real
 * broker-adapters/mt5-bridge-gateway process is NOT automated here — same honest limitation every
 * prior broker-adjacent ticket in this codebase flags (see BrokerAccountOAuthIntegrationTest's own
 * Javadoc); only the ownership gate that runs BEFORE that network hop is verified.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerAccountCrudIntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
  }

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

  private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);

  private String generateTotpCode(String secret) {
    try {
      return codeGenerator.generate(secret, Instant.now().getEpochSecond() / 30);
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
    return (String) login.body().get("access_token");
  }

  private String loginAsWithTotp(String email, String secret) {
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of(
                "email",
                email,
                "password",
                "correct horse battery staple",
                "totp_code",
                generateTotpCode(secret)),
            null);
    return (String) login.body().get("access_token");
  }

  private record NewUser(UUID userId, String accessToken) {}

  /** Every new user here is 2FA-enrolled for real, mirroring every sibling test file's helper. */
  private NewUser loginNewUser() {
    String email = "broker-crud-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    // TICKET-114 — a role-less caller is now "Individual mode" and subject to master/follower-
    // slot capability limits; these tests are about broker-linking mechanics generally, not that
    // new feature, so grant FOLLOWER (unaffected by any subscription/plan limit) same as every
    // real invited Follower would have by the time they reach this step.
    userProvisioningApi.grantRole(userId, "FOLLOWER");
    String preEnrollmentToken = loginAs(email);

    HttpResult enable = request("POST", "/api/v1/auth/2fa/enable", Map.of(), preEnrollmentToken);
    String secret = (String) enable.body().get("secret");
    request(
        "POST",
        "/api/v1/auth/2fa/verify",
        Map.of("totp_code", generateTotpCode(secret)),
        preEnrollmentToken);

    return new NewUser(userId, loginAsWithTotp(email, secret));
  }

  /**
   * TICKET-101 follow-up — the MASTER role must be granted BEFORE the final {@code loginAsWithTotp}
   * call: the access token's own {@code roles} claim is fixed at issuance, so granting a role to an
   * already-logged-in {@link #loginNewUser}'s caller would have no effect on their existing token
   * (a real mistake caught by this test itself failing the first time).
   */
  private NewUser loginNewMasterUser() {
    String email = "broker-crud-master-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    userProvisioningApi.grantRole(userId, "MASTER");
    String preEnrollmentToken = loginAs(email);

    HttpResult enable = request("POST", "/api/v1/auth/2fa/enable", Map.of(), preEnrollmentToken);
    String secret = (String) enable.body().get("secret");
    request(
        "POST",
        "/api/v1/auth/2fa/verify",
        Map.of("totp_code", generateTotpCode(secret)),
        preEnrollmentToken);

    return new NewUser(userId, loginAsWithTotp(email, secret));
  }

  /**
   * Direct SQL insert, mirroring BrokerAccountOAuthIntegrationTest's own insertRealBrokerAccount.
   */
  private UUID insertBrokerAccount(UUID userId, String connectionStatus) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Original Label', false, 'USD', ?, ?, ?)
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion(),
        connectionStatus);
    return accountId;
  }

  /**
   * Chains broker_accounts -> master_profiles -> broker_ib_links (the real FK chain
   * 011-deferred-foreign-keys.sql adds) so openedViaIbLinkId validation and the new
   * BrokerIbLinkController read endpoint have a real row to exercise.
   */
  private UUID insertActiveBrokerIbLink(UUID masterUserId) {
    UUID primaryBrokerAccountId = insertBrokerAccount(masterUserId, "CONNECTED");
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name)
        VALUES (?, ?, ?, 'Test Master')
        """,
        masterProfileId,
        masterUserId,
        primaryBrokerAccountId);
    UUID ibLinkId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_ib_links (id, master_profile_id, broker_type, broker_display_name, ib_referral_url_or_code, is_active)
        VALUES (?, ?, 'CTRADER', 'IC Markets', 'https://icmarkets.com/ref/abc123', true)
        """,
        ibLinkId,
        masterProfileId);
    return ibLinkId;
  }

  // TICKET-101/102 follow-up — server_name is now really persisted (not always NULL, see
  // BrokerAccountRepository's own class Javadoc), so broker_accounts' own UNIQUE(broker_type,
  // broker_account_login, server_name) constraint genuinely applies now. A fixed literal server
  // value collided with itself across repeated runs against this same persistent dev DB (no
  // per-test rollback here) — suffixing it per JVM run restores re-runnability.
  private static final String TEST_SERVER = "Pepperstone-Demo-" + UUID.randomUUID();

  private Map<String, Object> mt5LinkBody(String login) {
    return Map.of(
        "login",
        login,
        "password",
        "terminal-password-123",
        "server",
        TEST_SERVER,
        "is_demo",
        true,
        "display_label",
        "My MT5 Demo",
        "broker_name",
        "Pepperstone");
  }

  // ==================== list ====================

  @Test
  void list_withoutBearerToken_isRejected() {
    HttpResult response = request("GET", "/api/v1/broker-accounts", null, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void list_returnsOnlyTheCallersOwnAccounts_neverAnotherUsersRows() {
    NewUser userA = loginNewUser();
    NewUser userB = loginNewUser();
    UUID accountA1 = insertBrokerAccount(userA.userId(), "CONNECTED");
    UUID accountA2 = insertBrokerAccount(userA.userId(), "PENDING");
    insertBrokerAccount(userB.userId(), "CONNECTED");

    ListHttpResult response = requestList("/api/v1/broker-accounts", userA.accessToken());

    assertThat(response.status()).isEqualTo(200);
    List<String> ids = response.body().stream().map(a -> (String) a.get("id")).toList();
    assertThat(ids).containsExactlyInAnyOrder(accountA1.toString(), accountA2.toString());
  }

  // ==================== PATCH ====================

  @Test
  void patch_updatesDisplayLabelAndConnectionRole_onTheRealRow() {
    // TICKET-101 follow-up — MASTER_ONLY now requires the real MASTER role (see
    // MasterRoleRequiredException's own Javadoc), so this test's own caller needs it too.
    NewUser user = loginNewMasterUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + accountId,
            Map.of("display_label", "Renamed", "connection_role", "MASTER_ONLY"),
            user.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("display_label")).isEqualTo("Renamed");
    assertThat(response.body().get("connection_role")).isEqualTo("MASTER_ONLY");

    String storedLabel =
        jdbcTemplate.queryForObject(
            "SELECT display_label FROM broker_accounts WHERE id = ?", String.class, accountId);
    String storedRole =
        jdbcTemplate.queryForObject(
            "SELECT connection_role FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(storedLabel).isEqualTo("Renamed");
    assertThat(storedRole).isEqualTo("MASTER_ONLY");
  }

  @Test
  void patch_toMasterOnly_withoutTheRealMasterRole_isRejected() {
    // loginNewUser() only grants FOLLOWER — a Follower (or Individual-mode) caller must never be
    // able to flip their own account to MASTER_ONLY and start broadcasting to real followers.
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + accountId,
            Map.of("connection_role", "MASTER_ONLY"),
            user.accessToken());

    assertThat(response.status()).isEqualTo(403);
    assertThat(response.body().get("error")).isEqualTo("master_role_required");

    String storedRole =
        jdbcTemplate.queryForObject(
            "SELECT connection_role FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(storedRole).isNotEqualTo("MASTER_ONLY");
  }

  @Test
  void patch_omittingAField_leavesItUnchanged() {
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + accountId,
            Map.of("display_label", "Only Label Changed"),
            user.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("display_label")).isEqualTo("Only Label Changed");
    // connection_role was never sent -- must retain its insert-time default (the DB column's own
    // default of 'BOTH', since insertBrokerAccount's raw SQL insert never sets it explicitly).
    assertThat(response.body().get("connection_role")).isEqualTo("BOTH");
  }

  @Test
  void patch_withInvalidConnectionRole_isRejectedAndLeavesTheRowUnchanged() {
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + accountId,
            Map.of("connection_role", "NOT_A_REAL_ROLE"),
            user.accessToken());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_connection_role");
    String storedRole =
        jdbcTemplate.queryForObject(
            "SELECT connection_role FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(storedRole).isEqualTo("BOTH");
  }

  @Test
  void patch_forAnotherUsersAccount_isForbidden() {
    NewUser owner = loginNewUser();
    NewUser attacker = loginNewUser();
    UUID accountId = insertBrokerAccount(owner.userId(), "CONNECTED");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + accountId,
            Map.of("display_label", "Hijacked"),
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void patch_forUnknownAccount_returns404() {
    NewUser user = loginNewUser();

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/broker-accounts/" + UUID.randomUUID(),
            Map.of("display_label", "Whatever"),
            user.accessToken());

    assertThat(response.status()).isEqualTo(404);
  }

  // ==================== DELETE ====================

  @Test
  void delete_removesTheRealRow() {
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    // TICKET-101 follow-up — disconnecting first is mandatory now, not optional.
    HttpResult disconnect =
        request(
            "POST",
            "/api/v1/broker-accounts/" + accountId + "/disconnect",
            null,
            user.accessToken());
    assertThat(disconnect.status()).isEqualTo(200);
    assertThat(disconnect.body().get("connection_status")).isEqualTo("DISCONNECTED");

    HttpResult response =
        request("DELETE", "/api/v1/broker-accounts/" + accountId, null, user.accessToken());
    assertThat(response.status()).isEqualTo(204);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM broker_accounts WHERE id = ?", Integer.class, accountId);
    assertThat(count).isZero();
  }

  @Test
  void delete_aStillConnectedAccount_isRejected() {
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED");

    HttpResult response =
        request("DELETE", "/api/v1/broker-accounts/" + accountId, null, user.accessToken());

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.body().get("error")).isEqualTo("broker_account_not_disconnected");
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM broker_accounts WHERE id = ?", Integer.class, accountId);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void disconnect_forAnotherUsersAccount_isForbidden() {
    NewUser owner = loginNewUser();
    NewUser attacker = loginNewUser();
    UUID accountId = insertBrokerAccount(owner.userId(), "CONNECTED");

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker-accounts/" + accountId + "/disconnect",
            null,
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
    String status =
        jdbcTemplate.queryForObject(
            "SELECT connection_status FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(status).isEqualTo("CONNECTED");
  }

  @Test
  void delete_forAnotherUsersAccount_isForbidden_andLeavesTheRowInPlace() {
    NewUser owner = loginNewUser();
    NewUser attacker = loginNewUser();
    UUID accountId = insertBrokerAccount(owner.userId(), "CONNECTED");

    HttpResult response =
        request("DELETE", "/api/v1/broker-accounts/" + accountId, null, attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM broker_accounts WHERE id = ?", Integer.class, accountId);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void delete_forUnknownAccount_returns404() {
    NewUser user = loginNewUser();

    HttpResult response =
        request("DELETE", "/api/v1/broker-accounts/" + UUID.randomUUID(), null, user.accessToken());

    assertThat(response.status()).isEqualTo(404);
  }

  // ==================== snapshot/positions ownership gate ====================
  // The live network hop these make to broker-adapters/mt5-bridge-gateway isn't automated here --
  // see this class's own Javadoc -- only the ownership gate that runs BEFORE that hop is verified.

  @Test
  void snapshot_forAnotherUsersAccount_isForbiddenBeforeAnyNetworkCall() {
    NewUser owner = loginNewUser();
    NewUser attacker = loginNewUser();
    UUID accountId = insertBrokerAccount(owner.userId(), "CONNECTED");

    HttpResult response =
        request(
            "GET",
            "/api/v1/broker-accounts/" + accountId + "/snapshot",
            null,
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void positions_forAnotherUsersAccount_isForbiddenBeforeAnyNetworkCall() {
    NewUser owner = loginNewUser();
    NewUser attacker = loginNewUser();
    UUID accountId = insertBrokerAccount(owner.userId(), "CONNECTED");

    HttpResult response =
        request(
            "GET",
            "/api/v1/broker-accounts/" + accountId + "/positions",
            null,
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  // ==================== openedViaIbLinkId + BrokerIbLinkController ====================

  @Test
  void ibLinks_forMasterWithAnActiveLink_returnsTheRealRow() {
    NewUser master = loginNewUser();
    UUID ibLinkId = insertActiveBrokerIbLink(master.userId());
    UUID masterProfileId =
        jdbcTemplate.queryForObject(
            "SELECT master_profile_id FROM broker_ib_links WHERE id = ?", UUID.class, ibLinkId);

    ListHttpResult response =
        requestList(
            "/api/v1/broker-accounts/ib-links?masterProfileId=" + masterProfileId,
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body())
        .anySatisfy(l -> assertThat(l.get("id")).isEqualTo(ibLinkId.toString()));
  }

  @Test
  void ibLinks_forMasterWithNoActiveLinks_returnsAnEmptyListNotAnError() {
    NewUser user = loginNewUser();

    ListHttpResult response =
        requestList(
            "/api/v1/broker-accounts/ib-links?masterProfileId=" + UUID.randomUUID(),
            user.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void mt5Link_withValidOpenedViaIbLinkId_recordsItOnTheRealRow() {
    NewUser master = loginNewUser();
    UUID ibLinkId = insertActiveBrokerIbLink(master.userId());
    NewUser follower = loginNewUser();

    HttpResult link =
        request(
            "POST",
            "/api/v1/broker-accounts/mt5",
            Map.of(
                "login",
                "700001",
                "password",
                "terminal-password-123",
                "server",
                TEST_SERVER,
                "is_demo",
                true,
                "display_label",
                "My MT5 Demo",
                "opened_via_ib_link_id",
                ibLinkId.toString()),
            follower.accessToken());

    assertThat(link.status()).isEqualTo(200);
    String accountId = (String) link.body().get("id");
    String storedIbLinkId =
        jdbcTemplate.queryForObject(
            "SELECT opened_via_ib_link_id FROM broker_accounts WHERE id = ?",
            String.class,
            UUID.fromString(accountId));
    assertThat(storedIbLinkId).isEqualTo(ibLinkId.toString());
  }

  @Test
  void mt5Link_withUnknownOpenedViaIbLinkId_isRejected() {
    NewUser user = loginNewUser();

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker-accounts/mt5",
            Map.of(
                "login",
                "700002",
                "password",
                "terminal-password-123",
                "server",
                TEST_SERVER,
                "is_demo",
                true,
                "display_label",
                "My MT5 Demo",
                "opened_via_ib_link_id",
                UUID.randomUUID().toString()),
            user.accessToken());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_ib_link");
  }
}
