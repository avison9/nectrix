package com.nectrix.coreapp.bootstrap.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
 * TICKET-123 — end-to-end verification of the terminal-pod-health join folded into {@code GET
 * /api/v1/admin/system-health}: a real, live HTTP round trip to a JDK {@link HttpServer} stub
 * standing in for apps/mt-terminal-host's new {@code /internal/terminals/status} (same "mocked
 * internal Go-service call" discipline {@code SymbolMappingIntegrationTest}'s own {@code
 * brokerAdapterStub} already established — apps/mt-terminal-host isn't part of this docker-compose
 * stack), proving the real join logic: an account with a matching pod in the stub's response gets
 * {@code podProvisioned=true} and its real phase/restart-count/ready fields; an account with NO
 * matching pod gets {@code podProvisioned=false} and every {@code pod*} field null, never a
 * fabricated "unhealthy" value.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Ticket123IntegrationTest {

  private static final String TEST_INTERNAL_SERVICE_TOKEN = "test-internal-service-token";

  /**
   * Set per-test (each test generates its own random account id, same "never a fixed UUID, always
   * randomized so repeated runs against this persistent, never-truncated dev database don't
   * collide" discipline every other integration test in this suite already follows — a real,
   * live-verified DuplicateKeyException on {@code broker_accounts_pkey} was hit here during this
   * ticket's own verification before this fix) — read by the stub handler below, which otherwise
   * has no per-request way to know which account id the CURRENT test expects a pod for.
   */
  private static final AtomicReference<String> currentHealthyAccountId = new AtomicReference<>("");

  private static final HttpServer terminalHostStub = startTerminalHostStub();

  @AfterAll
  static void stopTerminalHostStub() {
    terminalHostStub.stop(0);
  }

  /**
   * Started eagerly via a static field initializer, not {@code @BeforeAll} — mirrors
   * SymbolMappingIntegrationTest's own reasoning: {@code @DynamicPropertySource} methods run during
   * context preparation, before {@code @BeforeAll}, and need the stub's port already known. Only
   * reports a pod for {@code currentHealthyAccountId}'s current value — whatever other MT4/MT5
   * account ids the request's own real join logic pulls from Postgres (including this suite's own
   * historical seed data from prior test runs, never truncated) get no matching pod, mirroring a
   * torn-down/never-provisioned terminal for every one of them.
   */
  private static HttpServer startTerminalHostStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/internal/terminals/status",
          exchange -> {
            String presented = exchange.getRequestHeaders().getFirst("X-Internal-Service-Token");
            if (!TEST_INTERNAL_SERVICE_TOKEN.equals(presented)) {
              exchange.sendResponseHeaders(401, -1);
              exchange.close();
              return;
            }
            String json =
                """
                {"terminals":[{"brokerAccountId":"%s","podName":"mt-terminal-a1-xyz",
                 "phase":"Running","ready":true,"restartCount":3,
                 "lastTransitionTime":"2026-07-22T12:00:00Z"}]}
                """
                    .formatted(currentHealthyAccountId.get());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(bytes);
            }
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void internalServiceToken(DynamicPropertyRegistry registry) {
    registry.add("nectrix.internal.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
    registry.add(
        "nectrix.admin.mt-terminal-host.internal-base-url",
        () -> "http://localhost:" + terminalHostStub.getAddress().getPort());
    registry.add("nectrix.admin.mt-terminal-host.service-token", () -> TEST_INTERNAL_SERVICE_TOKEN);
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

  private UUID createTestUser(String email) {
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
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

  private String accessTokenFor(String email) {
    HttpResult login =
        request(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", "correct horse battery staple"),
            null);
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  private void seedMtBrokerAccount(String id, UUID ownerUserId, String brokerType, String login) {
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, currency, credentials_ciphertext,
           credentials_key_version, connection_status)
        VALUES (?::uuid, ?, ?, ?, 'USD', ?, 1, 'CONNECTED')
        """,
        id,
        ownerUserId,
        brokerType,
        login,
        "dummy-ciphertext".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void systemHealth_joinsRealPodStatuses_distinguishingProvisionedFromMissing() {
    String healthyAccountId = UUID.randomUUID().toString();
    String missingPodAccountId = UUID.randomUUID().toString();
    currentHealthyAccountId.set(healthyAccountId);

    String email = "ticket123-admin-" + UUID.randomUUID() + "@example.com";
    UUID adminId = createTestUser(email);
    grantRole(adminId, "ADMIN");
    String adminToken = accessTokenFor(email);

    seedMtBrokerAccount(healthyAccountId, adminId, "MT5", "ticket123-healthy-" + UUID.randomUUID());
    seedMtBrokerAccount(
        missingPodAccountId, adminId, "MT4", "ticket123-missing-" + UUID.randomUUID());

    HttpResult response = request("GET", "/api/v1/admin/system-health", null, adminToken);
    assertThat(response.status()).isEqualTo(200);

    Map<String, Object> mtTerminals = (Map<String, Object>) response.body().get("mt_terminals");
    assertThat(mtTerminals).isNotNull();
    assertThat(mtTerminals.get("reachable")).isEqualTo(true);

    List<Map<String, Object>> terminals = (List<Map<String, Object>>) mtTerminals.get("terminals");
    Map<String, Object> healthy =
        terminals.stream()
            .filter(t -> healthyAccountId.equals(t.get("broker_account_id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("healthy account missing from response"));
    assertThat(healthy.get("pod_provisioned")).isEqualTo(true);
    assertThat(healthy.get("pod_phase")).isEqualTo("Running");
    assertThat(healthy.get("pod_ready")).isEqualTo(true);
    assertThat(healthy.get("pod_restart_count")).isEqualTo(3);

    Map<String, Object> missing =
        terminals.stream()
            .filter(t -> missingPodAccountId.equals(t.get("broker_account_id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing-pod account missing from response"));
    assertThat(missing.get("pod_provisioned")).isEqualTo(false);
    assertThat(missing.get("pod_phase")).isNull();
    assertThat(missing.get("pod_ready")).isNull();
    assertThat(missing.get("pod_restart_count")).isNull();
  }

  @Test
  void systemHealth_supportCanView_noTokenIsRejected() {
    String email = "ticket123-support-" + UUID.randomUUID() + "@example.com";
    UUID supportId = createTestUser(email);
    grantRole(supportId, "SUPPORT");
    String supportToken = accessTokenFor(email);

    HttpResult withToken = request("GET", "/api/v1/admin/system-health", null, supportToken);
    assertThat(withToken.status()).isEqualTo(200);

    HttpResult noToken = request("GET", "/api/v1/admin/system-health", null, null);
    assertThat(noToken.status()).isEqualTo(401);
  }
}
