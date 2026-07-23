package com.nectrix.coreapp.bootstrap.analytics;

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
 * Feature — FollowerAnalyticsController's real equity-curve aggregation (across every broker
 * account the calling Follower has ever copied onto) and closed-P&amp;L computation, mirroring
 * MasterAnalyticsIntegrationTest's own real-HTTP-round-trip style. The one behavior genuinely new
 * to this endpoint (Master only ever has one primary account, so never needed this): merging
 * account_snapshots across MULTIPLE broker accounts into one summed daily curve.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FollowerAnalyticsIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

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

  private void grantRole(UUID userId, String roleName) {
    jdbcTemplate.update(
        "INSERT INTO user_roles (user_id, role_id) SELECT ?, r.id FROM roles r WHERE r.name = ?",
        userId,
        roleName);
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

  private record NewUser(UUID userId, String accessToken) {}

  private NewUser newUser(String... roles) {
    String email = "follower-analytics-" + UUID.randomUUID() + "@example.com";
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

  private void insertSnapshot(UUID brokerAccountId, Instant capturedAt, double equity) {
    jdbcTemplate.update(
        """
        INSERT INTO account_snapshots (broker_account_id, balance, equity, used_margin, free_margin, captured_at)
        VALUES (?, ?, ?, 0, ?, ?)
        """,
        brokerAccountId,
        equity,
        equity,
        equity,
        Timestamp.from(capturedAt));
  }

  /** Minimal chain linking an already-existing follower/broker account to a fresh Master. */
  private UUID insertRelationship(UUID followerUserId, UUID followerBrokerAccountId) {
    NewUser master = newUser("MASTER");
    UUID masterBrokerAccountId = insertBrokerAccount(master.userId());
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name) VALUES (?, ?, ?, 'Test Master')",
        masterProfileId,
        master.userId(),
        masterBrokerAccountId);
    UUID mmProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        mmProfileId);
    UUID riskProfileId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", riskProfileId);
    UUID invitationId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?)
        """,
        invitationId,
        masterProfileId,
        "invitee-" + invitationId + "@example.com",
        "token-hash-" + invitationId,
        followerUserId,
        Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));

    UUID relationshipId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', ?)
        """,
        relationshipId,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);
    return relationshipId;
  }

  private void insertClosedTrade(
      UUID relationshipId, String canonicalSymbol, BigDecimal realizedPnl, Instant closedAt) {
    UUID masterBrokerAccountId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT master_broker_account_id::text FROM copy_relationships WHERE id = ?",
                String.class,
                relationshipId));
    UUID tradeSignalId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO trade_signals
          (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol, direction,
           volume_lots, fill_price, server_timestamp, raw_payload)
        VALUES (?, ?, ?, 'POSITION_CLOSED', ?, 'BUY', 1.0, 1.1, ?, '{}'::jsonb)
        """,
        tradeSignalId,
        masterBrokerAccountId,
        "pos-" + tradeSignalId,
        canonicalSymbol,
        Timestamp.from(closedAt));
    UUID copiedTradeId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copied_trades
          (id, copy_relationship_id, trade_signal_id, idempotency_key, status, computed_volume_lots,
           sizing_method_snapshot, realized_pnl, closed_at)
        VALUES (?, ?, ?, ?, 'CLOSED', 1.0, '{}'::jsonb, ?, ?)
        """,
        copiedTradeId,
        relationshipId,
        tradeSignalId,
        "idem-" + copiedTradeId,
        realizedPnl,
        Timestamp.from(closedAt));
  }

  @Test
  void analytics_aggregatesEquityAcrossMultipleBrokerAccounts_forTheCallingFollower() {
    NewUser follower = newUser("FOLLOWER");
    UUID accountA = insertBrokerAccount(follower.userId());
    UUID accountB = insertBrokerAccount(follower.userId());
    insertRelationship(follower.userId(), accountA);
    insertRelationship(follower.userId(), accountB);

    Instant day = Instant.now().minus(1, ChronoUnit.DAYS);
    insertSnapshot(accountA, day, 5000.0);
    insertSnapshot(accountB, day, 3000.0);

    HttpResult response =
        request("GET", "/api/v1/followers/me/analytics?period=ALL", follower.accessToken());

    assertThat(response.status()).isEqualTo(200);
    List<Map<String, Object>> equityCurve =
        (List<Map<String, Object>>) response.body().get("equity_curve");
    assertThat(equityCurve).hasSize(1);
    assertThat(((Number) equityCurve.get(0).get("equity")).doubleValue()).isEqualTo(8000.0);
  }

  @Test
  void analytics_pnlByInstrument_scopedToTheCallingFollowerOnly_notOtherFollowers() {
    NewUser follower = newUser("FOLLOWER");
    UUID followerAccount = insertBrokerAccount(follower.userId());
    UUID relationshipId = insertRelationship(follower.userId(), followerAccount);
    insertClosedTrade(relationshipId, "EURUSD", new BigDecimal("125.00"), Instant.now());

    NewUser otherFollower = newUser("FOLLOWER");
    UUID otherAccount = insertBrokerAccount(otherFollower.userId());
    UUID otherRelationshipId = insertRelationship(otherFollower.userId(), otherAccount);
    insertClosedTrade(otherRelationshipId, "GBPUSD", new BigDecimal("999.00"), Instant.now());

    HttpResult response =
        request("GET", "/api/v1/followers/me/analytics?period=ALL", follower.accessToken());

    assertThat(response.status()).isEqualTo(200);
    List<Map<String, Object>> pnlByInstrument =
        (List<Map<String, Object>>) response.body().get("pnl_by_instrument");
    assertThat(pnlByInstrument).hasSize(1);
    assertThat(pnlByInstrument.get(0).get("canonical_symbol")).isEqualTo("EURUSD");
    assertThat(pnlByInstrument.get(0).get("total_pnl")).isEqualTo(125.0);
  }

  @Test
  void analytics_withNoRelationshipsAtAll_returnsEmptyNotAnError() {
    NewUser follower = newUser("FOLLOWER");

    HttpResult response =
        request("GET", "/api/v1/followers/me/analytics?period=30D", follower.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat((List<?>) response.body().get("equity_curve")).isEmpty();
    assertThat((List<?>) response.body().get("pnl_by_instrument")).isEmpty();
  }
}
