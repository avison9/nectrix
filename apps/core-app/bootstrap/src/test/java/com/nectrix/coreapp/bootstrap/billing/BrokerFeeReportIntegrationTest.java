package com.nectrix.coreapp.bootstrap.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import java.math.BigDecimal;
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
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-120 — real, hands-on proof of the Master-scoped BrokerFeeReport generate/list/detail/
 * send/confirm-deducted/confirm-paid endpoints: AC3 (exact bundling, scoped to master+broker), AC4
 * (correct per-line data), AC5 (status cascade + audit log), AC6 (object-level authorization).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerFeeReportIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private PerformanceFeeLedgerRepository ledgerRepository;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Map<String, Object> body) {}

  private record ListHttpResult(int status, List<Map<String, Object>> body) {}

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

  private NewUser newUser(String role) {
    String email = "fee-report-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    grantRole(userId, role);
    return new NewUser(userId, loginAs(email));
  }

  private UUID insertBrokerAccount(UUID userId, String brokerType) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, ?, ?, 'Original Label', false, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        brokerType,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private record MasterFixture(NewUser user, UUID masterProfileId) {}

  private MasterFixture newMaster() {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId(), "CTRADER");
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles
          (id, user_id, primary_broker_account_id, display_name, performance_fee_percent, fee_collection_method)
        VALUES (?, ?, ?, 'Test Master', 20.00, 'BROKER_PARTNERSHIP')
        """,
        masterProfileId,
        master.userId(),
        brokerAccountId);
    return new MasterFixture(master, masterProfileId);
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

  /**
   * Seeds a full copy_relationships row with a real master_broker_account of the given brokerType,
   * and one PENDING performance_fee_ledger row worth {@code feeAmount} against it.
   */
  private UUID seedPendingLedgerRow(
      MasterFixture master, String brokerType, String feeCollectionMethod, BigDecimal feeAmount) {
    UUID masterBrokerAccountId = insertBrokerAccount(master.user().userId(), brokerType);
    // A relationship's master_broker_account_id must belong to the SAME master — reassign
    // master_profiles.primary_broker_account_id is untouched; this is a genuine secondary
    // account the master runs this specific relationship's copy signal through.
    NewUser follower = newUser("FOLLOWER");
    UUID followerBrokerAccountId = insertBrokerAccount(follower.userId(), "CTRADER");
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(master.masterProfileId(), master.user().userId());

    UUID relationshipId = UUID.randomUUID();
    Instant riskAckAt = Instant.now().minus(40, ChronoUnit.DAYS);
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, high_water_mark, risk_ack_at, originating_invitation_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 20.00, ?, 10000, ?, ?, ?)
        """,
        relationshipId,
        master.masterProfileId(),
        masterBrokerAccountId,
        follower.userId(),
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        feeCollectionMethod,
        java.sql.Timestamp.from(riskAckAt),
        invitationId,
        java.sql.Timestamp.from(riskAckAt));

    UUID ledgerId =
        ledgerRepository
            .tryInsert(
                relationshipId,
                riskAckAt,
                riskAckAt.plus(30, ChronoUnit.DAYS),
                new BigDecimal("10000"),
                new BigDecimal("12000"),
                new BigDecimal("2000"),
                feeAmount,
                new BigDecimal("100"),
                new BigDecimal("1500"),
                "{}")
            .orElseThrow();
    return ledgerId;
  }

  private Map<String, Object> generateBody(String brokerType) {
    Instant now = Instant.now();
    return Map.of(
        "broker_type", brokerType,
        "period_start", now.minus(60, ChronoUnit.DAYS).toString(),
        "period_end", now.toString());
  }

  @Test
  void generate_bundlesExactlyThePendingBrokerPartnershipLedgerRowsForThisMasterAndBroker() {
    MasterFixture masterA = newMaster();
    UUID includedCtrader1 =
        seedPendingLedgerRow(masterA, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("400"));
    UUID includedCtrader2 =
        seedPendingLedgerRow(masterA, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("250"));
    // Excluded: same master, but STRIPE_INVOICE collection method.
    seedPendingLedgerRow(masterA, "CTRADER", "STRIPE_INVOICE", new BigDecimal("999"));
    // Excluded: same master, BROKER_PARTNERSHIP, but a DIFFERENT broker type (MT5, not CTRADER).
    seedPendingLedgerRow(masterA, "MT5", "BROKER_PARTNERSHIP", new BigDecimal("999"));
    // Excluded: a completely different master's own CTRADER/BROKER_PARTNERSHIP fee.
    MasterFixture masterB = newMaster();
    seedPendingLedgerRow(masterB, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("999"));

    HttpResult generated =
        request(
            "POST",
            "/api/v1/master/fee-reports",
            generateBody("CTRADER"),
            masterA.user().accessToken());
    assertThat(generated.status()).isEqualTo(200);
    String reportId = (String) generated.body().get("id");

    HttpResult detail =
        request(
            "GET", "/api/v1/master/fee-reports/" + reportId, null, masterA.user().accessToken());
    assertThat(detail.status()).isEqualTo(200);
    List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.body().get("lines");
    List<String> ledgerIds =
        lines.stream().map(l -> (String) l.get("performance_fee_ledger_id")).toList();
    assertThat(ledgerIds)
        .containsExactlyInAnyOrder(includedCtrader1.toString(), includedCtrader2.toString());
  }

  @Test
  void generate_lineData_matchesHandCalculatedFeeAmountAndFollowerLogin() {
    MasterFixture master = newMaster();
    BigDecimal expectedFee = new BigDecimal("437.50");
    seedPendingLedgerRow(master, "CTRADER", "BROKER_PARTNERSHIP", expectedFee);

    HttpResult generated =
        request(
            "POST",
            "/api/v1/master/fee-reports",
            generateBody("CTRADER"),
            master.user().accessToken());
    String reportId = (String) generated.body().get("id");
    HttpResult detail =
        request("GET", "/api/v1/master/fee-reports/" + reportId, null, master.user().accessToken());

    List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.body().get("lines");
    assertThat(lines).hasSize(1);
    Map<String, Object> line = lines.get(0);
    assertThat(BigDecimal.valueOf((Double) line.get("fee_amount")))
        .isEqualByComparingTo(expectedFee);
    assertThat((String) line.get("follower_broker_account_login")).startsWith("login-");
    assertThat(line.get("currency")).isEqualTo("USD");
  }

  @Test
  void send_thenConfirmDeducted_thenConfirmPaid_cascadesLedgerStatusAndWritesAuditLog() {
    MasterFixture master = newMaster();
    UUID ledgerId =
        seedPendingLedgerRow(master, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("300"));
    HttpResult generated =
        request(
            "POST",
            "/api/v1/master/fee-reports",
            generateBody("CTRADER"),
            master.user().accessToken());
    String reportId = (String) generated.body().get("id");

    HttpResult sent =
        request(
            "POST",
            "/api/v1/master/fee-reports/" + reportId + "/send",
            Map.of(),
            master.user().accessToken());
    assertThat(sent.status()).isEqualTo(200);
    assertThat(sent.body().get("status")).isEqualTo("SENT");
    assertThat(ledgerStatus(ledgerId)).isEqualTo("REPORTED_TO_BROKER");

    HttpResult confirmedDeducted =
        request(
            "POST",
            "/api/v1/master/fee-reports/" + reportId + "/confirm-deducted",
            Map.of(),
            master.user().accessToken());
    assertThat(confirmedDeducted.status()).isEqualTo(200);
    assertThat(confirmedDeducted.body().get("status")).isEqualTo("BROKER_CONFIRMED_DEDUCTED");
    assertThat(ledgerStatus(ledgerId)).isEqualTo("BROKER_CONFIRMED_DEDUCTED");

    HttpResult confirmedPaid =
        request(
            "POST",
            "/api/v1/master/fee-reports/" + reportId + "/confirm-paid",
            Map.of(),
            master.user().accessToken());
    assertThat(confirmedPaid.status()).isEqualTo(200);
    assertThat(confirmedPaid.body().get("status")).isEqualTo("BROKER_CONFIRMED_PAID");
    assertThat(ledgerStatus(ledgerId)).isEqualTo("BROKER_CONFIRMED_PAID");

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE target_type = 'broker_fee_report' AND target_id = ?",
            Long.class,
            reportId);
    assertThat(auditCount).isEqualTo(3L);
  }

  @Test
  void confirmDeducted_beforeSend_isRejected() {
    MasterFixture master = newMaster();
    seedPendingLedgerRow(master, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("300"));
    HttpResult generated =
        request(
            "POST",
            "/api/v1/master/fee-reports",
            generateBody("CTRADER"),
            master.user().accessToken());
    String reportId = (String) generated.body().get("id");

    HttpResult confirmedDeducted =
        request(
            "POST",
            "/api/v1/master/fee-reports/" + reportId + "/confirm-deducted",
            Map.of(),
            master.user().accessToken());
    assertThat(confirmedDeducted.status()).isEqualTo(409);
  }

  @Test
  void anotherMaster_cannotViewGenerateOrConfirm_thisMastersFeeReports() {
    MasterFixture masterA = newMaster();
    seedPendingLedgerRow(masterA, "CTRADER", "BROKER_PARTNERSHIP", new BigDecimal("300"));
    HttpResult generated =
        request(
            "POST",
            "/api/v1/master/fee-reports",
            generateBody("CTRADER"),
            masterA.user().accessToken());
    String reportId = (String) generated.body().get("id");

    MasterFixture masterB = newMaster();

    HttpResult viewAttempt =
        request(
            "GET", "/api/v1/master/fee-reports/" + reportId, null, masterB.user().accessToken());
    assertThat(viewAttempt.status()).isEqualTo(404);

    HttpResult sendAttempt =
        request(
            "POST",
            "/api/v1/master/fee-reports/" + reportId + "/send",
            Map.of(),
            masterB.user().accessToken());
    assertThat(sendAttempt.status()).isEqualTo(404);

    // masterB's own list never includes masterA's report.
    ListHttpResult listB = requestList("/api/v1/master/fee-reports", masterB.user().accessToken());
    assertThat(listB.status()).isEqualTo(200);
    assertThat(listB.body()).isEmpty();
  }

  private String ledgerStatus(UUID ledgerId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM performance_fee_ledger WHERE id = ?", String.class, ledgerId);
  }
}
