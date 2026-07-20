package com.nectrix.coreapp.bootstrap.archival;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.api.BrokerAccountArchivalApi;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 follow-up — end-to-end proof that {@link BrokerAccountArchivalOrchestrator} and its
 * two triggers (the on-demand {@code /archive-and-delete} endpoint and {@link
 * BrokerAccountArchivalJob}'s scheduled sweep) really do archive every referencing row to a real
 * MinIO object before deleting the {@code broker_accounts}/{@code copy_relationships}/{@code
 * copied_trades}/{@code trade_signals}/{@code performance_fee_ledger}/{@code management_agreements}
 * rows they touch, in the FK-safe order those tables require.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerAccountArchivalIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private PerformanceFeeLedgerRepository ledgerRepository;
  @Autowired private BrokerAccountArchivalApi brokerAccountArchivalApi;
  @Autowired private BrokerAccountArchivalOrchestrator orchestrator;
  @Autowired private ArchivalStorageProperties storageProperties;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  // ==================== HTTP plumbing (mirrors BrokerAccountCrudIntegrationTest)
  // ====================

  private record HttpResult(int status, Map<String, Object> body) {}

  private HttpResult request(String method, String path, String bearerToken) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .method(method, HttpRequest.BodyPublishers.noBody());
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

  private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);

  private String generateTotpCode(String secret) {
    try {
      return codeGenerator.generate(secret, Instant.now().getEpochSecond() / 30);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String loginAs(String email) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(
                          Map.of("email", email, "password", "correct horse battery staple"))))
              .build();
      HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      return (String) objectMapper.readValue(response.body(), Map.class).get("access_token");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String loginAsWithTotp(String email, String secret) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(
                          Map.of(
                              "email",
                              email,
                              "password",
                              "correct horse battery staple",
                              "totp_code",
                              generateTotpCode(secret)))))
              .build();
      HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      return (String) objectMapper.readValue(response.body(), Map.class).get("access_token");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record NewUser(UUID userId, String accessToken) {}

  private NewUser loginNewUser() {
    String email = "archival-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    String preEnrollmentToken = loginAs(email);
    HttpRequest enableReq =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/auth/2fa/enable"))
            .header("Authorization", "Bearer " + preEnrollmentToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    String secret;
    try {
      HttpResponse<String> enableResp =
          httpClient.send(enableReq, HttpResponse.BodyHandlers.ofString());
      secret = (String) objectMapper.readValue(enableResp.body(), Map.class).get("secret");
      HttpRequest verifyReq =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/auth/2fa/verify"))
              .header("Authorization", "Bearer " + preEnrollmentToken)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(
                          Map.of("totp_code", generateTotpCode(secret)))))
              .build();
      httpClient.send(verifyReq, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new NewUser(userId, loginAsWithTotp(email, secret));
  }

  // ==================== seeding (mirrors SettlementIntegrationTest) ====================

  private UUID insertBrokerAccount(UUID userId, String connectionStatus, Instant updatedAt) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status, updated_at)
        VALUES (?, ?, 'CTRADER', ?, 'Original Label', false, 'USD', ?, ?, ?, ?)
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion(),
        connectionStatus,
        Timestamp.from(updatedAt));
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
        Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
    return id;
  }

  private record Seeded(
      UUID copyRelationshipId,
      UUID masterUserId,
      UUID masterBrokerAccountId,
      UUID followerBrokerAccountId,
      String masterAccessToken) {}

  /**
   * A full chain — copy_relationships, one closed copied_trade (+ its trade_signal), one
   * performance_fee_ledger row, one management_agreements row — all tied to a freshly-DISCONNECTED
   * master broker account, exercising every table {@link BrokerAccountArchivalOrchestrator} has to
   * touch.
   */
  private Seeded seedFullChain(Instant masterAccountUpdatedAt) {
    NewUser master = loginNewUser();
    NewUser follower = loginNewUser();
    // The master's PRIMARY broker account (master_profiles.primary_broker_account_id, NOT NULL,
    // no ON DELETE CASCADE — a master profile can never lose its primary account) is deliberately
    // a DIFFERENT, untouched account from the one under test here: a real master can run several
    // broker accounts, and this test is about archiving a stale SECONDARY one, not tearing down
    // the master's own identity.
    UUID primaryBrokerAccountId = insertBrokerAccount(master.userId(), "CONNECTED", Instant.now());
    UUID masterBrokerAccountId =
        insertBrokerAccount(master.userId(), "DISCONNECTED", masterAccountUpdatedAt);
    UUID followerBrokerAccountId =
        insertBrokerAccount(follower.userId(), "CONNECTED", Instant.now());
    UUID masterProfileId = insertMasterProfile(master.userId(), primaryBrokerAccountId);
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, master.userId());

    UUID copyRelationshipId = UUID.randomUUID();
    Instant riskAckAt = Instant.now().minus(40, ChronoUnit.DAYS);
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, high_water_mark, risk_ack_at, originating_invitation_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', 10000, ?, ?, ?)
        """,
        copyRelationshipId,
        masterProfileId,
        masterBrokerAccountId,
        follower.userId(),
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        Timestamp.from(riskAckAt),
        invitationId,
        Timestamp.from(riskAckAt));

    UUID tradeSignalId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO trade_signals
          (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol,
           direction, volume_lots, server_timestamp, raw_payload)
        VALUES (?, ?, ?, 'POSITION_CLOSED', 'EURUSD', 'BUY', 1.0, ?, '{}'::jsonb)
        """,
        tradeSignalId,
        masterBrokerAccountId,
        "pos-" + tradeSignalId,
        Timestamp.from(riskAckAt.plus(10, ChronoUnit.DAYS)));
    jdbcTemplate.update(
        """
        INSERT INTO copied_trades
          (id, copy_relationship_id, trade_signal_id, idempotency_key, status,
           computed_volume_lots, sizing_method_snapshot, realized_pnl, closed_at)
        VALUES (?, ?, ?, ?, 'CLOSED', 1.0, '{}'::jsonb, 2000, ?)
        """,
        UUID.randomUUID(),
        copyRelationshipId,
        tradeSignalId,
        "idem-" + tradeSignalId,
        Timestamp.from(riskAckAt.plus(10, ChronoUnit.DAYS)));

    ledgerRepository.tryInsert(
        copyRelationshipId,
        riskAckAt,
        riskAckAt.plus(30, ChronoUnit.DAYS),
        new BigDecimal("10000"),
        new BigDecimal("12000"),
        new BigDecimal("2000"),
        new BigDecimal("400"),
        new BigDecimal("100"),
        new BigDecimal("1500"),
        "{}");

    jdbcTemplate.update(
        """
        INSERT INTO management_agreements
          (id, copy_relationship_id, agreement_version, status, document_object_key)
        VALUES (?, ?, 'v1', 'SIGNED', ?)
        """,
        UUID.randomUUID(),
        copyRelationshipId,
        "agreements/" + copyRelationshipId + ".pdf");

    return new Seeded(
        copyRelationshipId,
        master.userId(),
        masterBrokerAccountId,
        followerBrokerAccountId,
        master.accessToken());
  }

  private S3Client testS3Client() {
    return S3Client.builder()
        .region(Region.of(storageProperties.region()))
        .endpointOverride(URI.create(storageProperties.endpointOverride()))
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    storageProperties.accessKey(), storageProperties.secretKey())))
        .build();
  }

  private List<Map<String, Object>> fetchAndParseJsonl(String blobKey) throws IOException {
    S3Client s3 = testS3Client();
    try (InputStream raw =
            s3.getObject(
                GetObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(blobKey)
                    .build());
        GZIPInputStream gunzipped = new GZIPInputStream(raw)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      gunzipped.transferTo(buffer);
      String text = buffer.toString(StandardCharsets.UTF_8);
      return text.lines().filter(line -> !line.isBlank()).map(this::parseLine).toList();
    } finally {
      s3.close();
    }
  }

  private Map<String, Object> parseLine(String line) {
    return objectMapper.readValue(line, Map.class);
  }

  // ==================== tests ====================

  @Test
  void
      archiveAndDelete_forADisconnectedAccountWithFullHistory_archivesToMinioThenDeletesEverything()
          throws IOException {
    Seeded seeded = seedFullChain(Instant.now().minus(200, ChronoUnit.DAYS));

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker-accounts/" + seeded.masterBrokerAccountId() + "/archive-and-delete",
            seeded.masterAccessToken());

    assertThat(response.status()).isEqualTo(200);
    // Raw wire JSON — app-wide spring.jackson.property-naming-strategy: SNAKE_CASE (see
    // application.yml), unlike @nectrix/api-client's own automatic snake_case->camelCase mapping.
    String blobKey = (String) response.body().get("blob_key");
    assertThat(blobKey).startsWith("broker-accounts/" + seeded.masterBrokerAccountId());

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM broker_accounts WHERE id = ?", seeded.masterBrokerAccountId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM copy_relationships WHERE id = ?", seeded.copyRelationshipId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM copied_trades WHERE copy_relationship_id = ?",
                seeded.copyRelationshipId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM trade_signals WHERE master_broker_account_id = ?",
                seeded.masterBrokerAccountId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM performance_fee_ledger WHERE copy_relationship_id = ?",
                seeded.copyRelationshipId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM management_agreements WHERE copy_relationship_id = ?",
                seeded.copyRelationshipId()))
        .isEmpty();

    List<Map<String, Object>> archivalLogRows =
        jdbcTemplate.queryForList(
            "SELECT blob_key, archived_row_counts::text AS counts FROM archival_log WHERE broker_account_id = ?",
            seeded.masterBrokerAccountId());
    assertThat(archivalLogRows).hasSize(1);
    assertThat(archivalLogRows.get(0).get("blob_key")).isEqualTo(blobKey);

    List<Map<String, Object>> lines = fetchAndParseJsonl(blobKey);
    assertThat(lines)
        .extracting(l -> l.get("table"))
        .containsExactlyInAnyOrder(
            "broker_accounts",
            "copy_relationships",
            "copied_trades",
            "trade_signals",
            "performance_fee_ledger",
            "management_agreements");
  }

  @Test
  void archiveAndDelete_forAStillConnectedAccount_isRejected() {
    NewUser user = loginNewUser();
    UUID accountId = insertBrokerAccount(user.userId(), "CONNECTED", Instant.now());

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker-accounts/" + accountId + "/archive-and-delete",
            user.accessToken());

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.body().get("error")).isEqualTo("broker_account_not_ready_for_archival");
    assertThat(jdbcTemplate.queryForList("SELECT id FROM broker_accounts WHERE id = ?", accountId))
        .hasSize(1);
  }

  @Test
  void findStaleDisconnected_returnsOnlyAccountsPastTheThreshold_skipsRecentlyDisconnected() {
    NewUser staleUser = loginNewUser();
    NewUser recentUser = loginNewUser();
    UUID staleAccountId =
        insertBrokerAccount(
            staleUser.userId(), "DISCONNECTED", Instant.now().minus(200, ChronoUnit.DAYS));
    UUID recentAccountId = insertBrokerAccount(recentUser.userId(), "DISCONNECTED", Instant.now());

    List<UUID> candidates =
        brokerAccountArchivalApi.findStaleDisconnected(java.time.Duration.ofDays(90));

    assertThat(candidates).contains(staleAccountId);
    assertThat(candidates).doesNotContain(recentAccountId);
  }

  @Test
  void sweep_oneAccountsFailure_doesNotAbortTheRestOfTheBatch() {
    Seeded ok = seedFullChain(Instant.now().minus(200, ChronoUnit.DAYS));
    NewUser failingUser = loginNewUser();
    UUID failingAccountId =
        insertBrokerAccount(
            failingUser.userId(), "DISCONNECTED", Instant.now().minus(200, ChronoUnit.DAYS));

    BrokerAccountArchivalOrchestrator spyOrchestrator = Mockito.spy(orchestrator);
    Mockito.doThrow(new RuntimeException("simulated failure"))
        .when(spyOrchestrator)
        .archiveAndDelete(failingAccountId);
    Mockito.doCallRealMethod().when(spyOrchestrator).archiveAndDelete(ok.masterBrokerAccountId());

    BrokerAccountArchivalJob job =
        new BrokerAccountArchivalJob(
            brokerAccountArchivalApi, spyOrchestrator, new ArchivalJobProperties(90 * 24 * 3600L));
    job.sweepStaleDisconnectedAccounts();

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM broker_accounts WHERE id = ?", ok.masterBrokerAccountId()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT id FROM broker_accounts WHERE id = ?", failingAccountId))
        .hasSize(1);
  }
}
