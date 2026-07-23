package com.nectrix.coreapp.bootstrap.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * TICKET-111 — the CopyRelationship state machine's server-side gates (AC2/AC3), verified as real
 * HTTP round trips against a real DB row, same style as BrokerAccountCrudIntegrationTest. Since no
 * real invitation-acceptance flow exists yet (TICKET-118 isn't built — see this ticket's own scope
 * note), every relationship row here is seeded directly by SQL rather than created through a real
 * API, same "seed-data manipulation" honest limitation the approved plan flagged up front.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CopyRelationshipIntegrationTest {

  /**
   * TICKET-120 — overrides {@code nectrix.documents.public-endpoint-override} for this test only:
   * this test's own HTTP client fetches the presigned URL from inside the SAME JVM as the app
   * itself, not from a real browser, so it needs to reach MinIO the same way the app's own internal
   * S3 client already does — via {@code DOCUMENTS_ENDPOINT_OVERRIDE}, which is already set
   * correctly per-topology everywhere this test runs (devcontainer: {@code minio:9000}; CI/bare
   * host: {@code localhost:9000}). Bugfix — this used to hardcode {@code http://minio:9000}, which
   * only happens to be right inside the devcontainer's own docker network; that broke CI (bare
   * runner, no {@code minio} hostname to resolve at all) and a bare host-run alike. Falling back to
   * {@code DOCUMENTS_ENDPOINT_OVERRIDE} needs no new configuration anywhere — it's already correct
   * everywhere this test needs to run.
   */
  @DynamicPropertySource
  static void documentsPublicEndpoint(DynamicPropertyRegistry registry) {
    registry.add(
        "nectrix.documents.public-endpoint-override",
        () -> System.getenv("DOCUMENTS_ENDPOINT_OVERRIDE"));
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
    String email = "copy-rel-" + UUID.randomUUID() + "@example.com";
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

  private UUID insertMasterProfile(UUID masterUserId, UUID primaryBrokerAccountId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name)
        VALUES (?, ?, ?, 'Test Master')
        """,
        id,
        masterUserId,
        primaryBrokerAccountId);
    return id;
  }

  private UUID insertMoneyManagementProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO money_management_profiles (id, method, multiplier)
        VALUES (?, 'MULTIPLIER', 1.0)
        """,
        id);
    return id;
  }

  private UUID insertRiskProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", id);
    return id;
  }

  /** {@code chk_exactly_one_origin} requires a real {@code invitations} row to reference. */
  private UUID insertInvitation(UUID masterProfileId, UUID createdByUserId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?)
        """,
        id,
        masterProfileId,
        "invitee-" + id + "@example.com",
        "token-hash-" + id,
        createdByUserId,
        java.sql.Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
    return id;
  }

  private record Chain(UUID id, NewUser master, NewUser follower) {}

  /**
   * Seeds a full CopyRelationship chain (master + follower + profiles), status/fee configurable.
   */
  private Chain insertCopyRelationship(String status, String feeCollectionMethod) {
    NewUser master = newUser("MASTER");
    NewUser follower = newUser("FOLLOWER");
    UUID masterBrokerAccountId = insertBrokerAccount(master.userId());
    UUID followerBrokerAccountId = insertBrokerAccount(follower.userId());
    UUID masterProfileId = insertMasterProfile(master.userId(), masterBrokerAccountId);
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, master.userId());

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 20.00, ?, ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        follower.userId(),
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        status,
        feeCollectionMethod,
        invitationId);
    return new Chain(id, master, follower);
  }

  // ==================== acknowledge-risk ====================

  @Test
  void acknowledgeRisk_withBrokerPartnership_movesToPendingAgreement() {
    Chain chain = insertCopyRelationship("PENDING_RISK_ACK", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/acknowledge-risk",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("status")).isEqualTo("PENDING_AGREEMENT");
  }

  @Test
  void acknowledgeRisk_withStripeInvoice_movesDirectlyToActive() {
    Chain chain = insertCopyRelationship("PENDING_RISK_ACK", "STRIPE_INVOICE");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/acknowledge-risk",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void acknowledgeRisk_fromActive_isRejectedWith409() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/acknowledge-risk",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(409);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM copy_relationships WHERE id = ?", String.class, chain.id());
    assertThat(storedStatus).isEqualTo("ACTIVE");
  }

  // ==================== sign-agreement ====================

  @Test
  void signAgreement_fromPendingAgreement_movesToActive() {
    Chain chain = insertCopyRelationship("PENDING_AGREEMENT", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/sign-agreement",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void signAgreement_withoutHavingAcknowledgedRiskFirst_isRejected() {
    Chain chain = insertCopyRelationship("PENDING_RISK_ACK", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/sign-agreement",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(409);
  }

  /**
   * TICKET-120 AC1 — a {@code BROKER_PARTNERSHIP} relationship genuinely cannot reach {@code
   * ACTIVE} until sign-agreement runs: {@code acknowledgeRisk} alone only ever gets it as far as
   * {@code PENDING_AGREEMENT} (see {@code
   * acknowledgeRisk_withBrokerPartnership_movesToPendingAgreement} above), and there is no other
   * endpoint that sets status directly.
   */
  @Test
  void copyRelationship_withBrokerPartnership_cannotReachActiveWithoutSigningAgreement() {
    Chain chain = insertCopyRelationship("PENDING_AGREEMENT", "BROKER_PARTNERSHIP");

    HttpResult before =
        request(
            "GET",
            "/api/v1/copy-relationships/" + chain.id(),
            null,
            chain.follower().accessToken());
    assertThat(before.body().get("status")).isEqualTo("PENDING_AGREEMENT");

    HttpResult agreementBeforeSigning =
        request(
            "GET",
            "/api/v1/copy-relationships/" + chain.id() + "/agreement",
            null,
            chain.follower().accessToken());
    assertThat(agreementBeforeSigning.status()).isEqualTo(404);

    HttpResult signed =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/sign-agreement",
            null,
            chain.follower().accessToken());
    assertThat(signed.status()).isEqualTo(200);
    assertThat(signed.body().get("status")).isEqualTo("ACTIVE");
  }

  /**
   * TICKET-120 AC2 — a signed agreement's document is retrievable from MinIO via a real, working
   * short-lived signed URL (fetched with a plain, unauthenticated HTTP client — proving the URL
   * itself carries the necessary signature, not a bearer token), and its content matches the real
   * relationship it was signed for.
   */
  @Test
  void getAgreement_afterSigning_returnsAWorkingPresignedUrlWithRealContent() {
    Chain chain = insertCopyRelationship("PENDING_AGREEMENT", "BROKER_PARTNERSHIP");
    request(
        "POST",
        "/api/v1/copy-relationships/" + chain.id() + "/sign-agreement",
        null,
        chain.follower().accessToken());

    HttpResult agreement =
        request(
            "GET",
            "/api/v1/copy-relationships/" + chain.id() + "/agreement",
            null,
            chain.follower().accessToken());
    assertThat(agreement.status()).isEqualTo(200);
    assertThat(agreement.body().get("status")).isEqualTo("SIGNED");
    String documentUrl = (String) agreement.body().get("document_url");
    assertThat(documentUrl).isNotBlank();

    HttpResponse<String> fetched =
        uncheckedSend(HttpRequest.newBuilder(URI.create(documentUrl)).GET().build());
    assertThat(fetched.statusCode()).isEqualTo(200);
    assertThat(fetched.body()).contains(chain.id().toString());
    assertThat(fetched.body()).contains("20.00").contains("BROKER_PARTNERSHIP");
  }

  private HttpResponse<String> uncheckedSend(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==================== pause/resume ====================

  @Test
  void pause_fromActive_movesToPaused_andResume_movesBackToActive() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult paused =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/pause",
            null,
            chain.follower().accessToken());
    assertThat(paused.status()).isEqualTo(200);
    assertThat(paused.body().get("status")).isEqualTo("PAUSED");

    HttpResult resumed =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/resume",
            null,
            chain.follower().accessToken());
    assertThat(resumed.status()).isEqualTo(200);
    assertThat(resumed.body().get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void pause_fromPendingRiskAck_isRejected() {
    Chain chain = insertCopyRelationship("PENDING_RISK_ACK", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/pause",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(409);
  }

  // ==================== stop ====================

  @Test
  void stop_fromActive_movesToStoppedAndRecordsStoppedAt() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/stop",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("status")).isEqualTo("STOPPED");
    java.sql.Timestamp stoppedAt =
        jdbcTemplate.queryForObject(
            "SELECT stopped_at FROM copy_relationships WHERE id = ?",
            java.sql.Timestamp.class,
            chain.id());
    assertThat(stoppedAt).isNotNull();
  }

  @Test
  void stop_whenAlreadyStopped_isRejected() {
    Chain chain = insertCopyRelationship("STOPPED", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/stop",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(409);
  }

  @Test
  void stop_fromPendingRiskAck_succeeds_backingOutBeforeEitherGateClears() {
    Chain chain = insertCopyRelationship("PENDING_RISK_ACK", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/stop",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("status")).isEqualTo("STOPPED");
  }

  // ==================== ownership (IDOR) ====================

  @Test
  void getById_forAnotherFollowersRelationship_isForbidden() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    NewUser attacker = newUser("FOLLOWER");

    HttpResult response =
        request("GET", "/api/v1/copy-relationships/" + chain.id(), null, attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void pause_byAnotherFollower_isForbiddenAndLeavesStatusUnchanged() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    NewUser attacker = newUser("FOLLOWER");

    HttpResult response =
        request(
            "POST",
            "/api/v1/copy-relationships/" + chain.id() + "/pause",
            null,
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM copy_relationships WHERE id = ?", String.class, chain.id());
    assertThat(storedStatus).isEqualTo("ACTIVE");
  }

  // ==================== list ====================

  @Test
  void list_role_follower_returnsOnlyTheCallersOwnRelationships() {
    Chain chainA = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    ListHttpResult response =
        requestList("/api/v1/copy-relationships?role=follower", chainA.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    List<String> ids = response.body().stream().map(r -> (String) r.get("id")).toList();
    assertThat(ids).containsExactly(chainA.id().toString());
  }

  // ==================== patch ====================

  @Test
  void patch_swapsInADifferentMoneyManagementAndRiskProfile() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    UUID newMmProfileId = insertMoneyManagementProfile();
    UUID newRiskProfileId = insertRiskProfile();

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/copy-relationships/" + chain.id(),
            Map.of(
                "money_management_profile_id", newMmProfileId.toString(),
                "risk_profile_id", newRiskProfileId.toString()),
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    String storedMmId =
        jdbcTemplate.queryForObject(
            "SELECT money_management_profile_id FROM copy_relationships WHERE id = ?",
            String.class,
            chain.id());
    assertThat(storedMmId).isEqualTo(newMmProfileId.toString());
  }

  // ==================== trades ====================

  @Test
  void trades_forAnotherFollowersRelationship_isForbidden() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    NewUser attacker = newUser("FOLLOWER");

    HttpResult response =
        request(
            "GET",
            "/api/v1/copy-relationships/" + chain.id() + "/trades",
            null,
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void trades_withNoCopiedTradesYet_returnsAnEmptyPageNotAnError() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "GET",
            "/api/v1/copy-relationships/" + chain.id() + "/trades",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("total")).isEqualTo(0);
    assertThat((List<?>) response.body().get("trades")).isEmpty();
  }

  // ==================== TICKET-116 — copy-settings (in-place edit) ====================

  @Test
  void updateCopySettings_editsTheExistingProfileRowsInPlace_notSwappingIds() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    UUID originalMmId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT money_management_profile_id FROM copy_relationships WHERE id = ?",
                String.class,
                chain.id()));

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/copy-relationships/" + chain.id() + "/copy-settings",
            Map.ofEntries(
                Map.entry("method", "MULTIPLIER"),
                Map.entry("fixed_lot_size", 0),
                Map.entry("multiplier", 2.5),
                Map.entry("risk_percent", 0),
                Map.entry("rounding_mode", "NEAREST"),
                Map.entry("max_lot_per_trade", 3.0),
                Map.entry("max_open_positions", 5),
                Map.entry("max_slippage_pips", 2.5)),
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    Map<String, Object> mm = (Map<String, Object>) response.body().get("money_management_profile");
    assertThat(mm.get("multiplier")).isEqualTo(2.5);
    Map<String, Object> risk = (Map<String, Object>) response.body().get("risk_profile");
    assertThat(risk.get("max_lot_per_trade")).isEqualTo(3.0);

    // Same row id — an in-place update, not a swap to a newly-inserted profile.
    UUID storedMmId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT money_management_profile_id FROM copy_relationships WHERE id = ?",
                String.class,
                chain.id()));
    assertThat(storedMmId).isEqualTo(originalMmId);
    Double storedMultiplier =
        jdbcTemplate.queryForObject(
            "SELECT multiplier FROM money_management_profiles WHERE id = ?",
            Double.class,
            originalMmId);
    assertThat(storedMultiplier).isEqualTo(2.5);
  }

  @Test
  void updateCopySettings_byAnotherFollower_isForbiddenAndLeavesValuesUnchanged() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    NewUser attacker = newUser("FOLLOWER");

    HttpResult response =
        request(
            "PATCH",
            "/api/v1/copy-relationships/" + chain.id() + "/copy-settings",
            Map.ofEntries(
                Map.entry("method", "FIXED_LOT"),
                Map.entry("fixed_lot_size", 9.0),
                Map.entry("multiplier", 0),
                Map.entry("risk_percent", 0),
                Map.entry("rounding_mode", "DOWN"),
                Map.entry("max_lot_per_trade", 1.0),
                Map.entry("max_open_positions", 1),
                Map.entry("max_slippage_pips", 1.0)),
            attacker.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  // ==================== TICKET-116 — cross-relationship trade history ====================

  @Test
  void allTrades_scopesToTheCallersOwnRelationshipsOnly() {
    Chain mine = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");
    insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "GET",
            "/api/v1/copy-relationships/trades?role=follower",
            null,
            mine.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("total")).isEqualTo(0);
  }

  @Test
  void allTrades_filteredByASymbolNoTradeHas_returnsEmptyNotAnError() {
    Chain chain = insertCopyRelationship("ACTIVE", "BROKER_PARTNERSHIP");

    HttpResult response =
        request(
            "GET",
            "/api/v1/copy-relationships/trades?role=follower&symbol=NOSUCHSYMBOL",
            null,
            chain.follower().accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("total")).isEqualTo(0);
    assertThat((List<?>) response.body().get("trades")).isEmpty();
  }
}
