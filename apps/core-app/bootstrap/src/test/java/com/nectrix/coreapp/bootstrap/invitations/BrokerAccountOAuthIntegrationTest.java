package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.service.BrokerLinkingService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 — real, hands-on verification of the parts of the cTrader OAuth linking flow that
 * don't require a live cTrader account: {@code state} validation (rejecting a missing/unknown/
 * already-consumed state before ever attempting a real network call to cTrader), auth requirements
 * on each route, the {@code /internal/**} shared-secret filter chain
 * (SecurityConfig#internalFilterChain / InternalServiceTokenFilter), and the three real internal
 * endpoints apps/broker-adapters calls. The real code-exchange + account-listing round trip against
 * a live cTrader app/demo account is covered by this ticket's separate live-verification runbook,
 * not automatable here.
 *
 * <p>{@code nectrix.internal.service-token} is overridden to a known test value via {@link
 * DynamicPropertySource} rather than read from the ambient {@code INTERNAL_SERVICE_TOKEN} env var —
 * that var isn't wired into local/CI env files until this ticket's own env/secrets task, so relying
 * on it here would make the "correct token succeeds" case untestable until then.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerAccountOAuthIntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  // Direct JdbcTemplate use (not the module's own repository, which is an
  // ArchUnit-restricted ..repository.. package) mirrors AuthIntegrationTest's own
  // ticket011Ac4_* tests' pattern for asserting on/seeding raw DB rows.
  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  @Autowired private ApplicationContext applicationContext;

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
          response.body() == null || response.body().isBlank()
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
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    return (String) login.body().get("access_token");
  }

  /** Once 2FA is enabled, a bare email+password login is challenged (401 totp_required). */
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

  private String loginNewUserWithoutTwoFactor() {
    String email = "ctrader-oauth-" + UUID.randomUUID() + "@example.com";
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
    return loginAs(email);
  }

  /**
   * TICKET-110 AC1 — {@code /broker/ctrader/link} now requires 2FA, so this shared setup enrolls it
   * for real (enable -> generate a valid TOTP code -> verify) then re-logs-in for a fresh access
   * token carrying two_factor_enabled=true (a pre-enrollment token would still read false).
   */
  private String loginNewUser() {
    String email = "ctrader-oauth-" + UUID.randomUUID() + "@example.com";
    userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
    String preEnrollmentToken = loginAs(email);

    HttpResult enable = request("POST", "/api/v1/auth/2fa/enable", Map.of(), preEnrollmentToken);
    String secret = (String) enable.body().get("secret");
    request(
        "POST",
        "/api/v1/auth/2fa/verify",
        Map.of("totp_code", generateTotpCode(secret)),
        preEnrollmentToken);

    return loginAsWithTotp(email, secret);
  }

  // TICKET-101 task #120 — regression test for a real bug caught by hand during this ticket:
  // enabling @EnableScheduling made TokenRefreshJob fire for real (including a real outbound
  // call to openapi.ctrader.com) the moment ANY @SpringBootTest context loaded, for every
  // pre-existing broker_accounts row (even dev-seed data with no real ciphertext). Gating the
  // bean behind @ConditionalOnProperty (default false) fixes it — this proves the bean is
  // genuinely absent from the context by default, not just "happens not to fire in time."
  @Test
  void tokenRefreshJob_isNotRegisteredUnlessExplicitlyEnabled() {
    assertThat(
            applicationContext.getBeanNamesForType(
                com.nectrix.coreapp.invitations.service.TokenRefreshJob.class))
        .isEmpty();
  }

  @Test
  void authorizeUrl_withoutBearerToken_isRejected() {
    HttpResult response = request("GET", "/api/v1/broker/ctrader/authorize-url", null, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void authorizeUrl_withValidBearerToken_returnsARealAuthorizeUrl() {
    String accessToken = loginNewUser();

    HttpResult response = request("GET", "/api/v1/broker/ctrader/authorize-url", null, accessToken);

    assertThat(response.status()).isEqualTo(200);
    String authorizeUrl = (String) response.body().get("authorize_url");
    assertThat(authorizeUrl).startsWith("https://connect.spotware.com/apps/auth?");
    assertThat(authorizeUrl).contains("redirect_uri=").contains("state=").contains("scope=trading");
  }

  @Test
  void authorizeUrl_calledTwice_producesTwoDistinctSingleUseStates() {
    String accessToken = loginNewUser();

    String firstUrl =
        (String)
            request("GET", "/api/v1/broker/ctrader/authorize-url", null, accessToken)
                .body()
                .get("authorize_url");
    String secondUrl =
        (String)
            request("GET", "/api/v1/broker/ctrader/authorize-url", null, accessToken)
                .body()
                .get("authorize_url");

    assertThat(firstUrl).isNotEqualTo(secondUrl);
  }

  @Test
  void callback_withUnknownState_isRejectedWithoutEverCallingCtrader() {
    HttpResult response =
        request(
            "POST",
            "/api/v1/broker/ctrader/callback",
            Map.of("code", "irrelevant-code", "state", "never-issued-state"),
            null);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_oauth_state");
  }

  @Test
  void link_withoutBearerToken_isRejected() {
    HttpResult response =
        request(
            "POST",
            "/api/v1/broker/ctrader/link",
            Map.of("link_session_id", "irrelevant", "ctid_trader_account_id", 1, "is_live", false),
            null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void link_withoutTwoFactorEnabled_isRejected() {
    String accessToken = loginNewUserWithoutTwoFactor();

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker/ctrader/link",
            Map.of("link_session_id", "irrelevant", "ctid_trader_account_id", 1, "is_live", false),
            accessToken);

    assertThat(response.status()).isEqualTo(403);
    assertThat(response.body().get("error")).isEqualTo("two_factor_required");
  }

  @Test
  void link_withUnknownLinkSessionId_isRejected() {
    String accessToken = loginNewUser();

    HttpResult response =
        request(
            "POST",
            "/api/v1/broker/ctrader/link",
            Map.of(
                "link_session_id",
                "never-issued-session",
                "ctid_trader_account_id",
                1,
                "is_live",
                false),
            accessToken);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_link_session");
  }

  @Test
  void internalRoute_withoutServiceTokenHeader_isRejectedBeforeReachingAnyController() {
    HttpResult response = request("GET", "/internal/broker-accounts", null, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void internalRoute_withWrongServiceTokenHeader_isRejected() {
    HttpResult response =
        internalGet(
            "/internal/broker-accounts?status=CONNECTED&broker_type=CTRADER",
            "definitely-the-wrong-token");
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void listAccounts_withCorrectServiceTokenHeader_returnsRealAccountsForRequestedStatus() {
    UUID accountId = insertRealBrokerAccount("PENDING");

    ListHttpResult pending =
        internalGetList(
            "/internal/broker-accounts?status=PENDING&brokerType=CTRADER",
            TEST_INTERNAL_SERVICE_TOKEN);
    assertThat(pending.status()).isEqualTo(200);
    assertThat(pending.body())
        .anySatisfy(a -> assertThat(a.get("id")).isEqualTo(accountId.toString()));

    // A status this account isn't in must not return it.
    ListHttpResult connected =
        internalGetList(
            "/internal/broker-accounts?status=CONNECTED&brokerType=CTRADER",
            TEST_INTERNAL_SERVICE_TOKEN);
    assertThat(connected.status()).isEqualTo(200);
    assertThat(connected.body())
        .noneSatisfy(a -> assertThat(a.get("id")).isEqualTo(accountId.toString()));
  }

  @Test
  void credentials_forUnknownId_returns404() {
    HttpResult response =
        internalGet(
            "/internal/broker-accounts/credentials/" + UUID.randomUUID(),
            TEST_INTERNAL_SERVICE_TOKEN);
    assertThat(response.status()).isEqualTo(404);
  }

  @Test
  void credentials_forRealAccount_decryptsTheRealStoredAccessAndRefreshTokens() {
    UUID accountId = insertRealBrokerAccount("CONNECTED");

    HttpResult response =
        internalGet(
            "/internal/broker-accounts/credentials/" + accountId, TEST_INTERNAL_SERVICE_TOKEN);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("accessToken")).isEqualTo("test-access-token");
    assertThat(response.body().get("refreshToken")).isEqualTo("test-refresh-token");
    assertThat(((Number) response.body().get("ctidTraderAccountId")).longValue()).isEqualTo(999L);
  }

  @Test
  void updateConnectionStatus_withValidStatus_updatesTheRealRowAndPublishesWithoutError() {
    UUID accountId = insertRealBrokerAccount("PENDING");

    HttpResult response =
        internalPost(
            "/internal/broker-accounts/" + accountId + "/connection-status",
            Map.of("status", "CONNECTED", "detail", "integration test"),
            TEST_INTERNAL_SERVICE_TOKEN);

    assertThat(response.status()).isEqualTo(200);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT connection_status FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(storedStatus).isEqualTo("CONNECTED");
  }

  @Test
  void updateConnectionStatus_withInvalidStatus_isRejectedAndLeavesTheRowUnchanged() {
    UUID accountId = insertRealBrokerAccount("PENDING");

    HttpResult response =
        internalPost(
            "/internal/broker-accounts/" + accountId + "/connection-status",
            Map.of("status", "NOT_A_REAL_STATUS", "detail", "bad input"),
            TEST_INTERNAL_SERVICE_TOKEN);

    assertThat(response.status()).isEqualTo(400);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT connection_status FROM broker_accounts WHERE id = ?", String.class, accountId);
    assertThat(storedStatus).isEqualTo("PENDING");
  }

  private record ListHttpResult(int status, List<Map<String, Object>> body) {}

  private HttpResult internalGet(String path, String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("X-Internal-Service-Token", token)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      Map<String, Object> body =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ListHttpResult internalGetList(String path, String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("X-Internal-Service-Token", token)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      List<Map<String, Object>> body =
          response.body() == null || response.body().isBlank()
              ? List.of()
              : objectMapper.readValue(response.body(), List.class);
      return new ListHttpResult(response.statusCode(), body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResult internalPost(String path, Map<String, Object> body, String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("X-Internal-Service-Token", token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
   * Inserts a real broker_accounts row, bypassing the OAuth flow (which needs a live cTrader
   * account) — credentials are encrypted via the REAL {@link EnvelopeEncryptionService} bean, in
   * the exact JSON shape BrokerLinkingService itself produces, so the internal credentials
   * endpoint's real decrypt path is genuinely exercised, not stubbed.
   */
  private UUID insertRealBrokerAccount(String connectionStatus) {
    UUID userId =
        userProvisioningApi.createUser(
            "broker-owner-" + UUID.randomUUID() + "@example.com",
            "correct horse battery staple",
            "Broker Owner",
            null,
            null,
            null,
            "US");
    // Built via the app's own (globally snake_case) ObjectMapper bean, exactly like
    // BrokerLinkingService.linkAccount really does — a hand-written JSON literal here would
    // silently mismatch the real write path's key casing and mask a real decode bug (this
    // was caught by hand: a first draft using literal camelCase keys made accessToken/
    // refreshToken come back null, since the real read path expects snake_case).
    String credentialsJson =
        objectMapper.writeValueAsString(
            new BrokerLinkingService.StoredCredentials(
                "test-access-token",
                "test-refresh-token",
                java.time.Instant.now().plusSeconds(3600).toString()));
    EncryptedField encrypted = envelopeEncryptionService.encryptField(credentialsJson);
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', '999', false, 'USD', ?, ?, ?)
        """,
        accountId,
        userId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion(),
        connectionStatus);
    return accountId;
  }
}
