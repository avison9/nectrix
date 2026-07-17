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
 * TICKET-114 — the self-serve Individual copy-setup endpoint, verified as real HTTP round trips
 * (same style as {@code MasterProfileIntegrationTest}) rather than calling {@code
 * IndividualCopySetupService} directly — that service's ownership checks flow through {@code
 * BrokerAccountLookupApi.getBrokerAccount}, which is {@code @PostAuthorize}-guarded and needs a
 * real {@code SecurityContext} populated by the JWT filter chain, not just a bare method call.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndividualCopySetupIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

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
    String email = "indiv-copy-" + UUID.randomUUID() + "@example.com";
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
        VALUES (?, ?, 'CTRADER', ?, 'Label', true, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private Map<String, Object> setupBody(UUID main, UUID slave) {
    return Map.of(
        "main_broker_account_id", main.toString(), "slave_broker_account_id", slave.toString());
  }

  @Test
  void setUp_createsPrivateProfileAndCopyRelationship() {
    NewUser user = newUser("USER");
    UUID main = insertBrokerAccount(user.userId());
    UUID slave = insertBrokerAccount(user.userId());

    HttpResult response =
        request(
            "POST", "/api/v1/individual/copy-setup", setupBody(main, slave), user.accessToken());

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("master_broker_account_id")).isEqualTo(main.toString());
    assertThat(response.body().get("follower_broker_account_id")).isEqualTo(slave.toString());

    UUID masterProfileId = UUID.fromString((String) response.body().get("master_profile_id"));
    Boolean isPublic =
        jdbcTemplate.queryForObject(
            "SELECT is_public FROM master_profiles WHERE id = ?", Boolean.class, masterProfileId);
    assertThat(isPublic).isFalse();

    UUID copyRelationshipId = UUID.fromString((String) response.body().get("id"));
    Boolean originatingIndividualSetup =
        jdbcTemplate.queryForObject(
            "SELECT originating_individual_setup FROM copy_relationships WHERE id = ?",
            Boolean.class,
            copyRelationshipId);
    assertThat(originatingIndividualSetup).isTrue();
  }

  @Test
  void setUp_calledTwice_reusesTheSamePrivateProfile() {
    NewUser user = newUser("USER");
    UUID main = insertBrokerAccount(user.userId());
    UUID slaveOne = insertBrokerAccount(user.userId());
    UUID slaveTwo = insertBrokerAccount(user.userId());

    HttpResult first =
        request(
            "POST", "/api/v1/individual/copy-setup", setupBody(main, slaveOne), user.accessToken());
    HttpResult second =
        request(
            "POST", "/api/v1/individual/copy-setup", setupBody(main, slaveTwo), user.accessToken());

    assertThat(second.body().get("master_profile_id"))
        .isEqualTo(first.body().get("master_profile_id"));
    Long profileCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM master_profiles WHERE user_id = ?", Long.class, user.userId());
    assertThat(profileCount).isEqualTo(1L);
  }

  @Test
  void setUp_sameAccountForMainAndSlave_rejected() {
    NewUser user = newUser("USER");
    UUID account = insertBrokerAccount(user.userId());

    HttpResult response =
        request(
            "POST",
            "/api/v1/individual/copy-setup",
            setupBody(account, account),
            user.accessToken());

    assertThat(response.status()).isEqualTo(400);
  }

  @Test
  void setUp_slaveAccountOwnedByAnotherUser_rejected() {
    NewUser user = newUser("USER");
    NewUser otherUser = newUser("USER");
    UUID main = insertBrokerAccount(user.userId());
    UUID someoneElsesAccount = insertBrokerAccount(otherUser.userId());

    HttpResult response =
        request(
            "POST",
            "/api/v1/individual/copy-setup",
            setupBody(main, someoneElsesAccount),
            user.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }

  @Test
  void setUp_callerIsARealMaster_rejected() {
    NewUser user = newUser("USER", "MASTER");
    UUID main = insertBrokerAccount(user.userId());
    UUID slave = insertBrokerAccount(user.userId());

    HttpResult response =
        request(
            "POST", "/api/v1/individual/copy-setup", setupBody(main, slave), user.accessToken());

    assertThat(response.status()).isEqualTo(403);
  }
}
