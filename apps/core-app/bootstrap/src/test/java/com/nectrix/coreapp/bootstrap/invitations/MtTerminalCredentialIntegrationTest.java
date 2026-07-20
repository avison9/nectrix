package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.audit.repository.AuditLogRepository;
import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Nectrix-hosted MT5/MT4 terminal provisioning — real, hands-on verification that the new {@code
 * mt-terminal-credentials} endpoint is genuinely gated by its OWN separate token (not the shared
 * {@code X-Internal-Service-Token} every other internal caller uses), genuinely returns the real
 * plaintext password (unlike the sibling {@code mt-credentials} endpoint — see
 * BrokerAccountMtIntegrationTest's own confirmation that endpoint never returns it), and genuinely
 * writes an audit_log row on every successful fetch. Mirrors BrokerAccountMtIntegrationTest's
 * pattern.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MtTerminalCredentialIntegrationTest {

  private static final String SHARED_SERVICE_TOKEN = "test-internal-service-token";
  private static final String TERMINAL_PROVISIONER_TOKEN = "test-mt-terminal-provisioner-token";

  @DynamicPropertySource
  static void tokens(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> SHARED_SERVICE_TOKEN);
    registry.add(
        "nectrix.internal.mt-terminal-provisioner-token", () -> TERMINAL_PROVISIONER_TOKEN);
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AuditLogRepository auditLogRepository;

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

  private HttpResult internalGet(String path, String token) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
      if (token != null) {
        builder.header("X-Internal-Service-Token", token);
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, Object> body =
          response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
              ? Map.of()
              : objectMapper.readValue(response.body(), Map.class);
      return new HttpResult(response.statusCode(), body);
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

  /**
   * TICKET-110 AC1 — {@code /broker-accounts/mt5} now requires 2FA, so this shared setup enrolls it
   * for real then re-logs-in for a fresh access token (see BrokerAccountMtIntegrationTest's own
   * identical helper for the full reasoning).
   */
  private String loginNewUser() {
    String email = "mt-terminal-cred-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    // TICKET-114 — a role-less caller is now "Individual mode" and subject to master/follower-
    // slot capability limits; this test is about broker-linking mechanics generally, not that new
    // feature, so grant FOLLOWER (unaffected by any subscription/plan limit).
    userProvisioningApi.grantRole(userId, "FOLLOWER");
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

  // TICKET-101/102 follow-up — server_name is now really persisted, so broker_accounts' own
  // UNIQUE(broker_type, broker_account_login, server_name) constraint genuinely applies now — a
  // fixed literal server value collided with itself across repeated runs against this same
  // persistent dev DB. Suffixing it per JVM run restores re-runnability.
  private static final String TEST_SERVER = "Pepperstone-Demo-" + UUID.randomUUID();

  private String linkRealMt5Account(String login) {
    String accessToken = loginNewUser();
    HttpResult link =
        request(
            "POST",
            "/api/v1/broker-accounts/mt5",
            Map.of(
                "login",
                login,
                "password",
                "terminal-password-456",
                "server",
                TEST_SERVER,
                "is_demo",
                true,
                "display_label",
                "Terminal cred test",
                "broker_name",
                "Pepperstone"),
            accessToken);
    return (String) link.body().get("id");
  }

  @Test
  void mtTerminalCredentials_withoutAnyToken_isRejected() {
    String accountId = linkRealMt5Account("600001");
    HttpResult response =
        internalGet("/internal/broker-accounts/mt-terminal-credentials/" + accountId, null);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void mtTerminalCredentials_withTheSharedServiceToken_isRejected() {
    // The whole point of the separate token: a leaked/compromised broker-adapters or
    // mt5-bridge-gateway token must NOT be able to fetch a real plaintext password.
    String accountId = linkRealMt5Account("600002");
    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-terminal-credentials/" + accountId, SHARED_SERVICE_TOKEN);
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void mtTerminalCredentials_withTheCorrectProvisionerToken_returnsRealPlaintextPassword() {
    String accountId = linkRealMt5Account("600003");

    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-terminal-credentials/" + accountId,
            TERMINAL_PROVISIONER_TOKEN);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("login")).isEqualTo("600003");
    assertThat(response.body().get("password")).isEqualTo("terminal-password-456");
    assertThat(response.body().get("server")).isEqualTo(TEST_SERVER);
    assertThat((String) response.body().get("pairingToken")).isNotBlank();
  }

  @Test
  void mtTerminalCredentials_forUnknownId_returns404() {
    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-terminal-credentials/" + UUID.randomUUID(),
            TERMINAL_PROVISIONER_TOKEN);
    assertThat(response.status()).isEqualTo(404);
  }

  @Test
  void mtTerminalCredentials_onSuccess_writesARealAuditLogRow() {
    String accountId = linkRealMt5Account("600004");
    Instant before = Instant.now().minusSeconds(5);

    HttpResult response =
        internalGet(
            "/internal/broker-accounts/mt-terminal-credentials/" + accountId,
            TERMINAL_PROVISIONER_TOKEN);
    assertThat(response.status()).isEqualTo(200);

    var entries =
        auditLogRepository.findPage(
            new AuditLogRepository.Filter(null, "broker_account", accountId, before, null), 0, 10);
    assertThat(entries)
        .anySatisfy(
            e -> {
              assertThat(e.action()).isEqualTo("MT_TERMINAL_CREDENTIALS_FETCHED");
              assertThat(e.actorType()).isEqualTo("SYSTEM");
              assertThat(e.actorUserId()).isNull();
              assertThat(e.targetType()).isEqualTo("broker_account");
              assertThat(e.targetId()).isEqualTo(accountId);
            });
  }
}
