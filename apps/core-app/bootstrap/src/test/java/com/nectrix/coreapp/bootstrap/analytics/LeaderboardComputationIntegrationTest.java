package com.nectrix.coreapp.bootstrap.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.analytics.service.LeaderboardComputationService;
import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-112 — proves LeaderboardComputationService's computed metrics match hand-calculated values
 * from real seeded account_snapshots/copied_trades (AC1), that a below-eligibility- threshold
 * master is excluded from the ranked leaderboard listing while its own public profile still returns
 * metrics (AC2), and that the discovery API only ever serves from leaderboard_snapshots, never
 * recomputing live (AC3).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardComputationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  @Autowired private LeaderboardComputationService computationService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Map<String, Object> body) {}

  private record ListHttpResult(int status, List<Map<String, Object>> body) {}

  private HttpResult request(String method, String path, Map<String, Object> body) {
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

  private ListHttpResult requestList(String path) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder()
                  .uri(URI.create("http://localhost:" + port + path))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      List<Map<String, Object>> parsedBody =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("[")
              ? List.of()
              : objectMapper.readValue(response.body(), List.class);
      return new ListHttpResult(response.statusCode(), parsedBody);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID newUser() {
    String email = "leaderboard-" + UUID.randomUUID() + "@example.com";
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
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

  private void insertSnapshot(UUID brokerAccountId, Instant capturedAt, double equity) {
    jdbcTemplate.update(
        """
        INSERT INTO account_snapshots (broker_account_id, balance, equity, used_margin, free_margin, captured_at)
        VALUES (?, ?, ?, 0, ?, ?)
        """,
        brokerAccountId,
        BigDecimal.valueOf(equity),
        BigDecimal.valueOf(equity),
        BigDecimal.valueOf(equity),
        Timestamp.from(capturedAt));
  }

  /** Raw insert with an explicit created_at -- the normal repository insert always uses now(). */
  private UUID insertMasterProfile(UUID userId, UUID primaryBrokerAccountId, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name, is_public, created_at)
        VALUES (?, ?, ?, 'Test Master', true, ?)
        """,
        id,
        userId,
        primaryBrokerAccountId,
        Timestamp.from(createdAt));
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

  private UUID insertCopyRelationship(
      UUID masterProfileId,
      UUID masterBrokerAccountId,
      UUID followerUserId,
      UUID followerBrokerAccountId,
      String status) {
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, followerUserId);
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 20.00, 'BROKER_PARTNERSHIP', ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        status,
        invitationId);
    return id;
  }

  private UUID insertClosedCopiedTrade(
      UUID copyRelationshipId, UUID masterBrokerAccountId, double realizedPnl) {
    UUID tradeSignalId = insertTradeSignal(masterBrokerAccountId);
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copied_trades
          (id, copy_relationship_id, trade_signal_id, idempotency_key, status,
           computed_volume_lots, sizing_method_snapshot, realized_pnl, closed_at)
        VALUES (?, ?, ?, ?, 'CLOSED', 1.0, '{}'::jsonb, ?, now())
        """,
        id,
        copyRelationshipId,
        tradeSignalId,
        "idem-" + id,
        BigDecimal.valueOf(realizedPnl));
    return id;
  }

  private UUID insertTradeSignal(UUID masterBrokerAccountId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO trade_signals
          (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol,
           direction, volume_lots, server_timestamp, raw_payload)
        VALUES (?, ?, ?, 'POSITION_CLOSED', 'EURUSD', 'BUY', 1.0, now(), '{}'::jsonb)
        """,
        id,
        masterBrokerAccountId,
        "pos-" + id);
    return id;
  }

  @Test
  void computeAll_matchesHandCalculatedReturnAndDrawdown() {
    UUID masterUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID masterProfileId =
        insertMasterProfile(
            masterUserId, masterBrokerAccountId, Instant.now().minus(60, ChronoUnit.DAYS));

    Instant now = Instant.now();
    // day0=10000, day1=12000 (peak), day2=9000 (25% drawdown off 12000), day3=11000 (10% return
    // overall)
    insertSnapshot(masterBrokerAccountId, now.minus(3, ChronoUnit.DAYS), 10000);
    insertSnapshot(masterBrokerAccountId, now.minus(2, ChronoUnit.DAYS), 12000);
    insertSnapshot(masterBrokerAccountId, now.minus(1, ChronoUnit.DAYS), 9000);
    insertSnapshot(masterBrokerAccountId, now, 11000);

    computationService.computeAll();

    Map<String, Object> snapshot =
        jdbcTemplate.queryForMap(
            """
            SELECT return_pct, max_drawdown_pct FROM leaderboard_snapshots
            WHERE master_profile_id = ? AND period = 'ALL'
            ORDER BY computed_at DESC LIMIT 1
            """,
            masterProfileId);

    assertThat(((BigDecimal) snapshot.get("return_pct")).doubleValue())
        .isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
    assertThat(((BigDecimal) snapshot.get("max_drawdown_pct")).doubleValue())
        .isCloseTo(25.0, org.assertj.core.data.Offset.offset(0.01));
  }

  @Test
  void computeAll_winRateMatchesHandCalculatedCopiedTradesRatio() {
    UUID masterUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID masterProfileId =
        insertMasterProfile(
            masterUserId, masterBrokerAccountId, Instant.now().minus(60, ChronoUnit.DAYS));
    insertSnapshot(masterBrokerAccountId, Instant.now().minus(1, ChronoUnit.DAYS), 10000);
    insertSnapshot(masterBrokerAccountId, Instant.now(), 10000);

    UUID followerUserId = newUser();
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID relationshipId =
        insertCopyRelationship(
            masterProfileId,
            masterBrokerAccountId,
            followerUserId,
            followerBrokerAccountId,
            "ACTIVE");
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, 100);
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, 50);
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, -30);

    computationService.computeAll();

    BigDecimal winRatePct =
        jdbcTemplate.queryForObject(
            """
            SELECT win_rate_pct FROM leaderboard_snapshots
            WHERE master_profile_id = ? AND period = 'ALL' ORDER BY computed_at DESC LIMIT 1
            """,
            BigDecimal.class,
            masterProfileId);

    // 2 wins / 3 total = 66.67%
    assertThat(winRatePct.doubleValue())
        .isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
  }

  @Test
  void leaderboard_excludesMasterYoungerThan30Days_butProfileStillShowsMetrics() {
    UUID masterUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    // Created just now -- fails the 30-day-age eligibility gate.
    UUID masterProfileId = insertMasterProfile(masterUserId, masterBrokerAccountId, Instant.now());
    insertSnapshot(masterBrokerAccountId, Instant.now().minus(1, ChronoUnit.DAYS), 10000);
    insertSnapshot(masterBrokerAccountId, Instant.now(), 11000);
    computationService.computeAll();

    ListHttpResult leaderboard = requestList("/api/v1/discovery/leaderboard?period=ALL");
    assertThat(leaderboard.status()).isEqualTo(200);
    assertThat(leaderboard.body())
        .noneSatisfy(
            m -> assertThat(m.get("master_profile_id")).isEqualTo(masterProfileId.toString()));

    HttpResult profile = request("GET", "/api/v1/discovery/masters/" + masterProfileId, null);
    assertThat(profile.status()).isEqualTo(200);
  }

  @Test
  void leaderboard_excludesMasterWithFewerThan20ClosedTrades() {
    UUID masterUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID masterProfileId =
        insertMasterProfile(
            masterUserId, masterBrokerAccountId, Instant.now().minus(60, ChronoUnit.DAYS));
    insertSnapshot(masterBrokerAccountId, Instant.now().minus(1, ChronoUnit.DAYS), 10000);
    insertSnapshot(masterBrokerAccountId, Instant.now(), 11000);

    UUID followerUserId = newUser();
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID relationshipId =
        insertCopyRelationship(
            masterProfileId,
            masterBrokerAccountId,
            followerUserId,
            followerBrokerAccountId,
            "ACTIVE");
    // Only 3 closed trades -- well under the 20-trade eligibility threshold.
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, 100);
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, 50);
    insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, -30);
    computationService.computeAll();

    ListHttpResult leaderboard = requestList("/api/v1/discovery/leaderboard?period=ALL");
    assertThat(leaderboard.status()).isEqualTo(200);
    assertThat(leaderboard.body())
        .noneSatisfy(
            m -> assertThat(m.get("master_profile_id")).isEqualTo(masterProfileId.toString()));
  }

  @Test
  void discoveryApi_servesFromSnapshotOnly_notRecomputedLive() {
    UUID masterUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID masterProfileId =
        insertMasterProfile(
            masterUserId, masterBrokerAccountId, Instant.now().minus(60, ChronoUnit.DAYS));
    insertSnapshot(masterBrokerAccountId, Instant.now().minus(1, ChronoUnit.DAYS), 10000);
    insertSnapshot(masterBrokerAccountId, Instant.now(), 11000);

    UUID followerUserId = newUser();
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID relationshipId =
        insertCopyRelationship(
            masterProfileId,
            masterBrokerAccountId,
            followerUserId,
            followerBrokerAccountId,
            "ACTIVE");
    for (int i = 0; i < 20; i++) {
      insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, 100);
    }
    computationService.computeAll();

    HttpResult before = request("GET", "/api/v1/discovery/masters/" + masterProfileId, null);
    Map<String, Object> metricsBefore =
        (Map<String, Object>) before.body().get("metrics_by_period");
    Map<String, Object> allBefore = (Map<String, Object>) metricsBefore.get("ALL");
    assertThat(allBefore.get("win_rate_pct")).isEqualTo(100.0);

    // Mutate copied_trades AFTER the snapshot was taken -- if the discovery API were live-
    // querying, this would immediately change the served win rate; it must not.
    for (int i = 0; i < 20; i++) {
      insertClosedCopiedTrade(relationshipId, masterBrokerAccountId, -100);
    }

    HttpResult after = request("GET", "/api/v1/discovery/masters/" + masterProfileId, null);
    Map<String, Object> metricsAfter = (Map<String, Object>) after.body().get("metrics_by_period");
    Map<String, Object> allAfter = (Map<String, Object>) metricsAfter.get("ALL");
    assertThat(allAfter.get("win_rate_pct")).isEqualTo(100.0);
  }
}
