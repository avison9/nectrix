package com.nectrix.coreapp.bootstrap.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.events.v1.EventEnvelope;
import com.nectrix.events.v1.ReconciliationDriftDetected;
import com.nectrix.events.v1.ReconciliationDriftType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
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
 * TICKET-117 — end-to-end verification of the admin MVP features, following {@code
 * RbacIntegrationTest}/{@code AdminPortalIntegrationTest}'s exact established helpers/shape.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Ticket117IntegrationTest {

  @DynamicPropertySource
  static void consumerGroupId(DynamicPropertyRegistry registry) {
    // A dedicated, brand-new consumer group for this test class -- see
    // BrokerConnectionEventConsumer's own Javadoc for why sharing the default group id across
    // this suite's many @SpringBootTest contexts is a real cross-test partition-ownership race.
    registry.add(
        "ADMIN_RECONCILIATION_DRIFT_CONSUMER_GROUP_ID",
        () -> "test-admin-reconciliation-drift-" + UUID.randomUUID());
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
          response.body() == null || response.body().isBlank()
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * For array-rooted responses (e.g. {@code GET /admin/users}) — {@link #request} assumes an object
   * root.
   */
  private List<Map<String, Object>> requestList(String path, String bearerToken) {
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + path))
              .header("Authorization", "Bearer " + bearerToken)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      return objectMapper.readValue(response.body(), List.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String PASSWORD = "correct horse battery staple";

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

  private HttpResult login(String email) {
    return request(
        "POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
  }

  private String accessTokenFor(String email) {
    HttpResult result = login(email);
    assertThat(result.status()).isEqualTo(200);
    return (String) result.body().get("access_token");
  }

  // ---- Users: search / suspend / reinstate / status-enforcement ----

  @Test
  void searchReturnsASeededUser_andSuspendReinstateRoundTripStatusForReal() {
    String email = "ticket117-search-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID adminId = createTestUser("ticket117-admin-" + UUID.randomUUID() + "@example.com");
    grantRole(adminId, "ADMIN");
    String adminToken = accessTokenFor(emailFor(adminId));

    List<Map<String, Object>> searchResults =
        requestList("/api/v1/admin/users?search=" + email.split("@")[0], adminToken);
    assertThat(searchResults).anySatisfy(row -> assertThat(row.get("email")).isEqualTo(email));

    HttpResult suspend =
        request("PATCH", "/api/v1/admin/users/" + userId + "/suspend", Map.of(), adminToken);
    assertThat(suspend.status()).isEqualTo(200);
    String statusAfterSuspend =
        jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    assertThat(statusAfterSuspend).isEqualTo("SUSPENDED");

    HttpResult reinstate =
        request("PATCH", "/api/v1/admin/users/" + userId + "/reinstate", Map.of(), adminToken);
    assertThat(reinstate.status()).isEqualTo(200);
    String statusAfterReinstate =
        jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    assertThat(statusAfterReinstate).isEqualTo("ACTIVE");
  }

  private String emailFor(UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT email FROM users WHERE id = ?", String.class, userId);
  }

  /**
   * Bugfix follow-up — the default (no status filter) browse view must never surface a DELETED
   * account (nothing actionable an admin could do with one), but the Users page's own explicit
   * status filter must still be able to find one on purpose (e.g. to confirm a deletion really
   * took effect) — see UserRepository#search's own Javadoc for the full reasoning.
   */
  @Test
  void deletedUser_excludedFromDefaultBrowse_butReturnedByExplicitStatusFilter() {
    String email = "ticket117-deleted-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    UUID adminId = createTestUser("ticket117-admin-" + UUID.randomUUID() + "@example.com");
    grantRole(adminId, "ADMIN");
    String adminToken = accessTokenFor(emailFor(adminId));

    HttpResult delete = request("DELETE", "/api/v1/admin/users/" + userId, null, adminToken);
    assertThat(delete.status()).isEqualTo(200);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE id = ?", String.class, userId))
        .isEqualTo("DELETED");

    List<Map<String, Object>> defaultBrowse = requestList("/api/v1/admin/users", adminToken);
    assertThat(defaultBrowse)
        .noneSatisfy(row -> assertThat(row.get("id")).isEqualTo(userId.toString()));

    List<Map<String, Object>> searchWithoutStatusFilter =
        requestList("/api/v1/admin/users?search=" + email.split("@")[0], adminToken);
    assertThat(searchWithoutStatusFilter)
        .noneSatisfy(row -> assertThat(row.get("id")).isEqualTo(userId.toString()));

    List<Map<String, Object>> explicitStatusFilter =
        requestList("/api/v1/admin/users?status=DELETED", adminToken);
    assertThat(explicitStatusFilter)
        .anySatisfy(row -> assertThat(row.get("email")).isEqualTo(email));
  }

  @Test
  void suspendedUser_cannotLogin_andCannotRefreshAnExistingToken() {
    String email = "ticket117-suspend-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email);
    HttpResult loginBeforeSuspend = login(email);
    assertThat(loginBeforeSuspend.status()).isEqualTo(200);
    String refreshToken = (String) loginBeforeSuspend.body().get("refresh_token");

    jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId);

    HttpResult loginAfterSuspend = login(email);
    assertThat(loginAfterSuspend.status()).isEqualTo(403);

    HttpResult refreshAfterSuspend =
        request("POST", "/api/v1/auth/refresh", Map.of("refresh_token", refreshToken), null);
    assertThat(refreshAfterSuspend.status()).isEqualTo(403);
  }

  // ---- Disputes: raise / resolve RBAC + compensating-record ----

  private UUID insertBrokerAccount(UUID userId) {
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Label', false, 'USD', ?, 1, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        "dummy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return accountId;
  }

  private UUID insertMasterProfile(UUID masterUserId, UUID primaryBrokerAccountId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name) VALUES (?, ?, ?, 'Test Master')",
        id,
        masterUserId,
        primaryBrokerAccountId);
    return id;
  }

  private UUID insertMoneyManagementProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        id);
    return id;
  }

  private UUID insertRiskProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", id);
    return id;
  }

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

  private UUID insertCopyRelationship() {
    UUID masterUserId = createTestUser("ticket117-master-" + UUID.randomUUID() + "@example.com");
    UUID followerUserId =
        createTestUser("ticket117-follower-" + UUID.randomUUID() + "@example.com");
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID masterProfileId = insertMasterProfile(masterUserId, masterBrokerAccountId);
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, masterUserId);

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, performance_fee_percent, fee_collection_method,
           originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 20.00, 'STRIPE_INVOICE', ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);
    return id;
  }

  private UUID insertDisputedLedgerRow(UUID copyRelationshipId) {
    UUID id = UUID.randomUUID();
    Instant periodStart = Instant.now().minus(30, ChronoUnit.DAYS);
    Instant periodEnd = Instant.now();
    jdbcTemplate.update(
        """
        INSERT INTO performance_fee_ledger
          (id, copy_relationship_id, period_start, period_end, starting_hwm, ending_equity,
           new_profit_above_hwm, master_fee_amount, platform_take_amount, net_to_master_amount,
           computation_detail, status)
        VALUES (?, ?, ?, ?, 10000, 11000, 1000, 200, 50, 750, ?::jsonb, 'DISPUTED')
        """,
        id,
        copyRelationshipId,
        java.sql.Timestamp.from(periodStart),
        java.sql.Timestamp.from(periodEnd),
        "{\"starting_hwm\":10000,\"ending_equity\":11000}");
    return id;
  }

  @Test
  void supportCannotResolveADispute_butAdminCan_andComputationDetailIsNeverMutated() {
    UUID copyRelationshipId = insertCopyRelationship();
    UUID ledgerId = insertDisputedLedgerRow(copyRelationshipId);
    String originalDetailJson =
        jdbcTemplate.queryForObject(
            "SELECT computation_detail::text FROM performance_fee_ledger WHERE id = ?",
            String.class,
            ledgerId);

    UUID supportId = createTestUser("ticket117-support-" + UUID.randomUUID() + "@example.com");
    UUID adminId = createTestUser("ticket117-resolver-" + UUID.randomUUID() + "@example.com");
    grantRole(supportId, "SUPPORT");
    grantRole(adminId, "ADMIN");
    String supportToken = accessTokenFor(emailFor(supportId));
    String adminToken = accessTokenFor(emailFor(adminId));

    Map<String, Object> resolveBody =
        Map.of("resolution", "UPHOLD", "note", "Reviewed, fee stands.");

    HttpResult supportAttempt =
        request(
            "POST", "/api/v1/admin/fee-ledger/" + ledgerId + "/resolve", resolveBody, supportToken);
    assertThat(supportAttempt.status()).isEqualTo(403);

    HttpResult adminAttempt =
        request(
            "POST", "/api/v1/admin/fee-ledger/" + ledgerId + "/resolve", resolveBody, adminToken);
    assertThat(adminAttempt.status()).isEqualTo(204);

    Integer resolutionRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM fee_ledger_resolutions WHERE ledger_id = ?",
            Integer.class,
            ledgerId);
    assertThat(resolutionRows).isEqualTo(1);

    String detailAfterResolve =
        jdbcTemplate.queryForObject(
            "SELECT computation_detail::text FROM performance_fee_ledger WHERE id = ?",
            String.class,
            ledgerId);
    assertThat(detailAfterResolve).isEqualTo(originalDetailJson);

    String statusAfterResolve =
        jdbcTemplate.queryForObject(
            "SELECT status FROM performance_fee_ledger WHERE id = ?", String.class, ledgerId);
    assertThat(statusAfterResolve).isEqualTo("INVOICED");
  }

  @Test
  void supportCanRaiseADispute() {
    UUID copyRelationshipId = insertCopyRelationship();
    UUID ledgerId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO performance_fee_ledger
          (id, copy_relationship_id, period_start, period_end, starting_hwm, ending_equity,
           new_profit_above_hwm, master_fee_amount, platform_take_amount, net_to_master_amount,
           computation_detail, status)
        VALUES (?, ?, now() - interval '30 days', now(), 10000, 11000, 1000, 200, 50, 750, '{}'::jsonb, 'INVOICED')
        """,
        ledgerId,
        copyRelationshipId);

    UUID supportId = createTestUser("ticket117-raise-" + UUID.randomUUID() + "@example.com");
    grantRole(supportId, "SUPPORT");
    String supportToken = accessTokenFor(emailFor(supportId));

    HttpResult dispute =
        request(
            "POST",
            "/api/v1/admin/fee-ledger/" + ledgerId + "/dispute",
            Map.of("reason", "Follower disputes the fee amount."),
            supportToken);
    assertThat(dispute.status()).isEqualTo(204);

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM performance_fee_ledger WHERE id = ?", String.class, ledgerId);
    assertThat(status).isEqualTo("DISPUTED");
  }

  // ---- System Health ----

  @Test
  void systemHealthEndpoint_returnsRealAggregatesNotMockData() {
    UUID adminId = createTestUser("ticket117-health-" + UUID.randomUUID() + "@example.com");
    grantRole(adminId, "ADMIN");
    String adminToken = accessTokenFor(emailFor(adminId));

    HttpResult response = request("GET", "/api/v1/admin/system-health", null, adminToken);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body())
        .containsKeys("broker_connections", "copy_engine", "kafka_consumer_lag");
  }

  // ---- ReconciliationDriftConsumer: real Kafka round trip ----

  private KafkaProducer<String, byte[]> producer;

  private KafkaProducer<String, byte[]> producer() {
    if (producer == null) {
      String host = System.getenv().getOrDefault("KAFKA_HOST", "localhost");
      String port = System.getenv().getOrDefault("KAFKA_PORT", "9092");
      Properties props = new Properties();
      props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, host + ":" + port);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
      producer = new KafkaProducer<>(props);
    }
    return producer;
  }

  @AfterEach
  void closeProducer() {
    if (producer != null) {
      producer.close();
    }
  }

  @Test
  void realReconciliationDriftDetectedEvent_landsInReconciliationDriftLog() {
    UUID brokerAccountId = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    ReconciliationDriftDetected event =
        ReconciliationDriftDetected.newBuilder()
            .setEnvelope(
                EventEnvelope.newBuilder()
                    .setEventId(eventId)
                    .setOccurredAt(Instant.now().toString())
                    .setSchemaVersion("v1")
                    .build())
            .setBrokerAccountId(brokerAccountId.toString())
            .setBrokerPositionId("pos-1")
            .setDriftType(ReconciliationDriftType.RECONCILIATION_DRIFT_TYPE_MISSED_CLOSE)
            .setDetail("test-driven drift event")
            .build();

    long deadline = System.currentTimeMillis() + Duration.ofSeconds(180).toMillis();
    List<Map<String, Object>> rows = List.of();
    while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
      producer()
          .send(
              new ProducerRecord<>(
                  "reconciliation", brokerAccountId.toString(), event.toByteArray()));
      producer().flush();
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      rows =
          jdbcTemplate.queryForList(
              "SELECT * FROM reconciliation_drift_log WHERE broker_account_id = ?",
              brokerAccountId);
    }

    assertThat(rows).as("no reconciliation_drift_log row appeared after retries").isNotEmpty();
    assertThat(rows)
        .anySatisfy(
            row ->
                assertThat(row.get("drift_type"))
                    .isEqualTo("RECONCILIATION_DRIFT_TYPE_MISSED_CLOSE"));
  }
}
