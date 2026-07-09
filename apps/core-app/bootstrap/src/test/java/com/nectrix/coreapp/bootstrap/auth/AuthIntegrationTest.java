package com.nectrix.coreapp.bootstrap.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
 * End-to-end, hands-on verification of TICKET-005's ACs 2 through 6 (AC1 is covered by curling a
 * running instance directly — see the ticket's own plan; AC7's real Google OAuth round-trip needs
 * live user-supplied credentials and isn't automatable here). Runs against the ephemeral
 * Postgres+Redis started by {@code docker-compose.yml} (see {@code InfraConnectivitySmokeTest}),
 * never against production infra.
 *
 * <p>Uses plain {@code java.net.http.HttpClient} rather than {@code TestRestTemplate} — removed
 * entirely in Spring Boot 4 (Spring Framework 7's test module ships a new fluent {@code
 * RestTestClient} instead, but the plain JDK client needs no chasing of that new API's shape).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

  // TICKET-011 AC3 — logback's ConsoleAppender resolves System.out once, at
  // appender-start time (during Spring context creation), so redirecting it
  // has to happen in a static initializer (runs at class-load, before the
  // @SpringBootTest extension boots the context) rather than inside a
  // @BeforeEach/@Test. Tees everything to the real stdout too, so normal
  // console output/other tests' visibility is unaffected — this just also
  // mirrors every byte into an in-memory buffer we can inspect.
  private static final ByteArrayOutputStream LOG_CAPTURE_BUFFER = new ByteArrayOutputStream();

  static {
    PrintStream original = System.out;
    OutputStream tee =
        new OutputStream() {
          @Override
          public synchronized void write(int b) {
            original.write(b);
            LOG_CAPTURE_BUFFER.write(b);
          }

          @Override
          public synchronized void write(byte[] b, int off, int len) throws IOException {
            original.write(b, off, len);
            LOG_CAPTURE_BUFFER.write(b, off, len);
          }

          @Override
          public void flush() throws IOException {
            original.flush();
          }
        };
    System.setOut(new PrintStream(tee, true, StandardCharsets.UTF_8));
  }

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);

  private String baseUrl() {
    return "http://localhost:" + port + "/api/v1/auth";
  }

  private UUID createTestUser(String email, String password) {
    return userProvisioningApi.createUser(email, password, "Test User", null, null, null, "US");
  }

  private record HttpResult(int status, Map<String, Object> body) {}

  private HttpResult post(String path, Map<String, Object> body, String bearerToken) {
    try {
      String json = objectMapper.writeValueAsString(body);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json));
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

  private HttpResult login(String email, String password) {
    return post("/login", Map.of("email", email, "password", password), null);
  }

  @Test
  void ac2_internalCreateUserThenLogin_returnsValidTokenPair() {
    String email = "ac2-" + UUID.randomUUID() + "@example.com";
    createTestUser(email, "correct horse battery staple");

    HttpResult response = login(email, "correct horse battery staple");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).containsKeys("access_token", "refresh_token", "expires_in");
  }

  @Test
  void ac3_accessTokenHasFifteenMinuteExpiry_andRefreshRotates() {
    String email = "ac3-" + UUID.randomUUID() + "@example.com";
    createTestUser(email, "correct horse battery staple");

    Map<String, Object> loginBody = login(email, "correct horse battery staple").body();
    String accessToken = (String) loginBody.get("access_token");
    String refreshToken = (String) loginBody.get("refresh_token");

    long[] iatExp = decodeIatExp(accessToken);
    long ttlSeconds = iatExp[1] - iatExp[0];
    assertThat(ttlSeconds).isEqualTo(15 * 60);

    HttpResult refreshResponse = post("/refresh", Map.of("refresh_token", refreshToken), null);
    assertThat(refreshResponse.status()).isEqualTo(200);
    String newRefreshToken = (String) refreshResponse.body().get("refresh_token");
    assertThat(newRefreshToken).isNotEqualTo(refreshToken);

    // Old refresh token must now be rejected (it was rotated/revoked).
    HttpResult reuseResponse = post("/refresh", Map.of("refresh_token", refreshToken), null);
    assertThat(reuseResponse.status()).isEqualTo(401);
  }

  @Test
  void ac4_reusingRotatedRefreshTokenRevokesAllSessionsForUser() {
    String email = "ac4-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email, "correct horse battery staple");

    // Two independent sessions (e.g. two devices) for the same user.
    String refreshTokenA =
        (String) login(email, "correct horse battery staple").body().get("refresh_token");
    login(email, "correct horse battery staple"); // session B

    // Rotate session A once — refreshTokenA is now a "used" token.
    post("/refresh", Map.of("refresh_token", refreshTokenA), null);

    // Reuse of the now-rotated token must be rejected...
    HttpResult reuse = post("/refresh", Map.of("refresh_token", refreshTokenA), null);
    assertThat(reuse.status()).isEqualTo(401);

    // ...and must have revoked every session row for this user, including session B.
    Integer activeSessions =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            userId);
    assertThat(activeSessions).isZero();

    Integer reuseDetectedRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_reason = 'REUSE_DETECTED'",
            Integer.class,
            userId);
    assertThat(reuseDetectedRows).isGreaterThan(0);
  }

  @Test
  void ac5_twoFactorEnrollmentAndLoginChallenge() {
    String email = "ac5-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email, "correct horse battery staple");
    String accessToken =
        (String) login(email, "correct horse battery staple").body().get("access_token");

    HttpResult enableResponse = post("/2fa/enable", Map.of(), accessToken);
    assertThat(enableResponse.status()).isEqualTo(200);
    String secret = (String) enableResponse.body().get("secret");
    assertThat(secret).isNotBlank();

    // Wrong code must be rejected, and must NOT flip two_factor_enabled.
    HttpResult wrongCode = post("/2fa/verify", Map.of("totp_code", "000000"), accessToken);
    assertThat(wrongCode.status()).isEqualTo(422);

    String validCode = generateTotpCode(secret);
    HttpResult verifyResponse = post("/2fa/verify", Map.of("totp_code", validCode), accessToken);
    assertThat(verifyResponse.status()).isEqualTo(204);

    Boolean twoFactorEnabled =
        jdbcTemplate.queryForObject(
            "SELECT two_factor_enabled FROM users WHERE id = ?", Boolean.class, userId);
    assertThat(twoFactorEnabled).isTrue();

    // Login without a totp_code must now be challenged, not silently succeed.
    HttpResult loginNoCode = login(email, "correct horse battery staple");
    assertThat(loginNoCode.status()).isEqualTo(401);
    assertThat(loginNoCode.body().get("error")).isEqualTo("totp_required");

    // Login with the correct current code succeeds.
    String loginCode = generateTotpCode(secret);
    HttpResult loginWithCode =
        post(
            "/login",
            Map.of(
                "email", email, "password", "correct horse battery staple", "totp_code", loginCode),
            null);
    assertThat(loginWithCode.status()).isEqualTo(200);
  }

  @Test
  void ac6_rateLimitsRepeatedFailedLogins() {
    String email = "ac6-" + UUID.randomUUID() + "@example.com";
    createTestUser(email, "correct horse battery staple");

    int lastStatus = -1;
    for (int i = 0; i < 6; i++) {
      lastStatus = login(email, "wrong password").status();
      if (i < 5) {
        assertThat(lastStatus).isEqualTo(401);
      }
    }
    assertThat(lastStatus).isEqualTo(429);
  }

  // TICKET-011 AC3: "No plaintext secret ever appears in application logs."
  // TwoFactorService.beginEnrollment deliberately logs the real plaintext
  // secret via StructuredArguments.kv("secret", ...) (mirroring
  // HelloController's TICKET-010 precedent) specifically so this test can
  // prove logback-spring.xml's MaskingJsonGeneratorDecorator actually
  // redacts it on this real code path, not just HelloController's.
  @Test
  void ticket011Ac3_twoFactorEnrollment_neverLogsThePlaintextSecret() {
    String email = "t11ac3-" + UUID.randomUUID() + "@example.com";
    createTestUser(email, "correct horse battery staple");
    String accessToken =
        (String) login(email, "correct horse battery staple").body().get("access_token");

    int startOffset;
    synchronized (LOG_CAPTURE_BUFFER) {
      startOffset = LOG_CAPTURE_BUFFER.size();
    }

    HttpResult enableResponse = post("/2fa/enable", Map.of(), accessToken);
    assertThat(enableResponse.status()).isEqualTo(200);
    String secret = (String) enableResponse.body().get("secret");
    assertThat(secret).isNotBlank();

    String capturedLogs;
    synchronized (LOG_CAPTURE_BUFFER) {
      capturedLogs = LOG_CAPTURE_BUFFER.toString(StandardCharsets.UTF_8).substring(startOffset);
    }

    assertThat(capturedLogs).contains("2fa enrollment started");
    assertThat(capturedLogs).doesNotContain(secret);
    assertThat(capturedLogs).contains("\"secret\":\"****\"");
  }

  // TICKET-011 AC4: "The users.two_factor_secret column, inspected directly,
  // is not the plaintext value — confirming real encryption at rest, not
  // just an opaque-looking API response."
  @Test
  void ticket011Ac4_twoFactorSecretColumn_storesCiphertextNotPlaintext() {
    String email = "t11ac4-" + UUID.randomUUID() + "@example.com";
    UUID userId = createTestUser(email, "correct horse battery staple");
    String accessToken =
        (String) login(email, "correct horse battery staple").body().get("access_token");

    HttpResult enableResponse = post("/2fa/enable", Map.of(), accessToken);
    String secret = (String) enableResponse.body().get("secret");

    String storedCiphertext =
        jdbcTemplate.queryForObject(
            "SELECT two_factor_secret FROM users WHERE id = ?", String.class, userId);
    Short storedKeyVersion =
        jdbcTemplate.queryForObject(
            "SELECT two_factor_secret_key_version FROM users WHERE id = ?", Short.class, userId);

    assertThat(storedCiphertext).isNotEqualTo(secret);
    assertThat(storedCiphertext).doesNotContain(secret);
    assertThat(storedKeyVersion).isNotNull();
  }

  private String generateTotpCode(String secret) {
    try {
      return codeGenerator.generate(secret, Instant.now().getEpochSecond() / 30);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Decodes a JWT's second (payload) segment just enough to pull {@code iat}/{@code exp}. */
  private long[] decodeIatExp(String jwt) {
    String[] parts = jwt.split("\\.");
    String payloadJson =
        new String(
            Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
    long iat = extractLongField(payloadJson, "iat");
    long exp = extractLongField(payloadJson, "exp");
    return new long[] {iat, exp};
  }

  private long extractLongField(String json, String field) {
    int idx = json.indexOf("\"" + field + "\":");
    String rest = json.substring(idx + field.length() + 3);
    StringBuilder digits = new StringBuilder();
    for (char c : rest.toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      } else if (digits.length() > 0) {
        break;
      }
    }
    return Long.parseLong(digits.toString());
  }
}
