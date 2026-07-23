package com.nectrix.coreapp.bootstrap.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * TICKET-111 — MasterProfileService's 403 (non-MASTER)/409 (already has one)/broker-account
 * ownership gates, verified as real HTTP round trips against a real DB row, same style as
 * BrokerAccountCrudIntegrationTest.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterProfileIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

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
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * TICKET-125 — {@code GET /api/v1/master-profiles/me} returns a JSON array now (a user may own
   * more than one profile), so it needs its own list-shaped parse instead of {@link #request}'s
   * object-shaped one.
   */
  private List<Map<String, Object>> getMyProfiles(String accessToken) {
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/master-profiles/me"))
              .header("Authorization", "Bearer " + accessToken)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(response.body(), List.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void grantRole(UUID userId, String roleName) {
    jdbcTemplate.update(
        """
        INSERT INTO user_roles (user_id, role_id)
        SELECT ?, r.id FROM roles r WHERE r.name = ?
        """,
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
    return (String) login.body().get("access_token");
  }

  private record NewUser(UUID userId, String accessToken) {}

  private NewUser newUser(String... roles) {
    String email = "master-profile-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    for (String role : roles) {
      grantRole(userId, role);
    }
    return new NewUser(userId, loginAs(email));
  }

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

  private Map<String, Object> createRequestBody(UUID brokerAccountId) {
    return Map.of(
        "broker_account_id",
        brokerAccountId.toString(),
        "display_name",
        "Test Master",
        "strategy_tags",
        List.of("Scalping", "EURUSD"),
        "fee_collection_method",
        "BROKER_PARTNERSHIP");
  }

  // ==================== create ====================

  @Test
  void create_asMaster_succeedsAndStoresTheRealRow() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());

    HttpResult response =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("display_name")).isEqualTo("Test Master");
    String storedUserId =
        jdbcTemplate.queryForObject(
            "SELECT user_id FROM master_profiles WHERE id = ?",
            String.class,
            UUID.fromString((String) response.body().get("id")));
    assertThat(storedUserId).isEqualTo(master.userId().toString());
  }

  @Test
  void create_withMinFollowerBalance_storesAndReturnsIt() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    Map<String, Object> body = new HashMap<>(createRequestBody(brokerAccountId));
    body.put("min_follower_balance", 500);

    HttpResult response = request("POST", "/api/v1/master-profiles", body, master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(((Number) response.body().get("min_follower_balance")).doubleValue())
        .isEqualTo(500.0);
    BigDecimal stored =
        jdbcTemplate.queryForObject(
            "SELECT min_follower_balance FROM master_profiles WHERE id = ?",
            BigDecimal.class,
            UUID.fromString((String) response.body().get("id")));
    assertThat(stored).isEqualByComparingTo("500");
  }

  /**
   * Feature — 0 is the explicit "clear a previously-set minimum" sentinel (this codebase has no
   * null-vs-absent-field convention for PATCH requests) — see MasterProfileService's own
   * normalizeMinFollowerBalance Javadoc.
   */
  @Test
  void patch_withZeroMinFollowerBalance_clearsIt() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    Map<String, Object> createBody = new HashMap<>(createRequestBody(brokerAccountId));
    createBody.put("min_follower_balance", 500);
    HttpResult created =
        request("POST", "/api/v1/master-profiles", createBody, master.accessToken());
    String profileId = (String) created.body().get("id");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId,
            Map.of("display_name", "Test Master", "is_public", true, "min_follower_balance", 0),
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("min_follower_balance")).isNull();
    BigDecimal stored =
        jdbcTemplate.queryForObject(
            "SELECT min_follower_balance FROM master_profiles WHERE id = ?",
            BigDecimal.class,
            UUID.fromString(profileId));
    assertThat(stored).isNull();
  }

  @Test
  void create_asNonMaster_isForbidden() {
    NewUser follower = newUser("FOLLOWER");
    UUID brokerAccountId = insertBrokerAccount(follower.userId());

    HttpResult response =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            follower.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void create_whenAlreadyHasOne_returns409WithExistingProfileId() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    HttpResult first =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());
    String existingId = (String) first.body().get("id");

    HttpResult second =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().get("existing_profile_id")).isEqualTo(existingId);
  }

  @Test
  void create_withAnotherUsersBrokerAccount_isForbidden() {
    NewUser master = newUser("MASTER");
    NewUser someoneElse = newUser("MASTER");
    UUID someoneElsesAccountId = insertBrokerAccount(someoneElse.userId());

    HttpResult response =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(someoneElsesAccountId),
            master.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  // ==================== get ====================

  @Test
  void get_forAnotherUsersProfile_isForbidden() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");
    NewUser attacker = newUser("MASTER");

    HttpResult response =
        request("GET", "/api/v1/master-profiles/" + profileId, null, attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  // ==================== patch ====================

  @Test
  void patch_updatesSettingsOnTheRealRow() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId,
            Map.of("display_name", "Renamed Master", "is_public", true),
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("display_name")).isEqualTo("Renamed Master");
    Boolean storedIsPublic =
        jdbcTemplate.queryForObject(
            "SELECT is_public FROM master_profiles WHERE id = ?",
            Boolean.class,
            UUID.fromString(profileId));
    assertThat(storedIsPublic).isTrue();
  }

  // ==================== TICKET-116 — me ====================

  @Test
  void getMyProfile_returnsTheCallersOwnProfile_notSomeoneElses() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(brokerAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");

    List<Map<String, Object>> profiles = getMyProfiles(master.accessToken());

    assertThat(profiles).hasSize(1);
    assertThat(profiles.get(0).get("id")).isEqualTo(profileId);
  }

  @Test
  void getMyProfile_withNoProfileYet_returnsAnEmptyListNotAnError() {
    NewUser master = newUser("MASTER");

    List<Map<String, Object>> profiles = getMyProfiles(master.accessToken());

    assertThat(profiles).isEmpty();
  }

  // ==================== Bugfix — change primary broker account ====================

  @Test
  void changePrimaryBrokerAccount_toOwnedAccount_updatesTheRealRow() {
    NewUser master = newUser("MASTER");
    UUID oldAccountId = insertBrokerAccount(master.userId());
    UUID newAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(oldAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId + "/primary-broker-account",
            Map.of("broker_account_id", newAccountId.toString()),
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("new_broker_account_id")).isEqualTo(newAccountId.toString());
    assertThat(response.body().get("old_broker_account_id")).isEqualTo(oldAccountId.toString());
    String stored =
        jdbcTemplate.queryForObject(
            "SELECT primary_broker_account_id::text FROM master_profiles WHERE id = ?",
            String.class,
            UUID.fromString(profileId));
    assertThat(stored).isEqualTo(newAccountId.toString());
  }

  @Test
  void changePrimaryBrokerAccount_toAnotherUsersAccount_isForbidden() {
    NewUser master = newUser("MASTER");
    UUID oldAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(oldAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");
    NewUser someoneElse = newUser("MASTER");
    UUID someoneElsesAccountId = insertBrokerAccount(someoneElse.userId());

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId + "/primary-broker-account",
            Map.of("broker_account_id", someoneElsesAccountId.toString()),
            master.accessToken());

    assertThat(response.status()).isEqualTo(403);
    String stored =
        jdbcTemplate.queryForObject(
            "SELECT primary_broker_account_id::text FROM master_profiles WHERE id = ?",
            String.class,
            UUID.fromString(profileId));
    assertThat(stored).isEqualTo(oldAccountId.toString());
  }

  @Test
  void changePrimaryBrokerAccount_forAnotherUsersProfile_isForbidden() {
    NewUser master = newUser("MASTER");
    UUID oldAccountId = insertBrokerAccount(master.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(oldAccountId),
            master.accessToken());
    String profileId = (String) created.body().get("id");
    NewUser attacker = newUser("MASTER");
    UUID attackersOwnAccountId = insertBrokerAccount(attacker.userId());

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId + "/primary-broker-account",
            Map.of("broker_account_id", attackersOwnAccountId.toString()),
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  /**
   * The whole point of this feature — this is the exact bug that stopped a Master's real trades
   * from ever being copied: {@code copy_relationships.master_broker_account_id} used to be frozen
   * at invitation-acceptance time forever. Confirms it now moves with the primary account change.
   */
  @Test
  void changePrimaryBrokerAccount_cascadesToAnActiveCopyRelationship() {
    NewUser master = newUser("MASTER");
    NewUser follower = newUser("FOLLOWER");
    UUID oldAccountId = insertBrokerAccount(master.userId());
    UUID newAccountId = insertBrokerAccount(master.userId());
    UUID followerAccountId = insertBrokerAccount(follower.userId());
    HttpResult created =
        request(
            "POST",
            "/api/v1/master-profiles",
            createRequestBody(oldAccountId),
            master.accessToken());
    UUID profileId = UUID.fromString((String) created.body().get("id"));

    UUID mmProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        mmProfileId);
    UUID riskProfileId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", riskProfileId);
    UUID invitationId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, now() + interval '7 days')
        """,
        invitationId,
        profileId,
        "invitee-" + invitationId + "@example.com",
        "token-hash-" + invitationId,
        master.userId());
    UUID copyRelationshipId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', ?)
        """,
        copyRelationshipId,
        profileId,
        oldAccountId,
        follower.userId(),
        followerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/master-profiles/" + profileId + "/primary-broker-account",
            Map.of("broker_account_id", newAccountId.toString()),
            master.accessToken());

    assertThat(response.status()).isEqualTo(200);
    String storedMasterBrokerAccountId =
        jdbcTemplate.queryForObject(
            "SELECT master_broker_account_id::text FROM copy_relationships WHERE id = ?",
            String.class,
            copyRelationshipId);
    assertThat(storedMasterBrokerAccountId).isEqualTo(newAccountId.toString());
  }
}
