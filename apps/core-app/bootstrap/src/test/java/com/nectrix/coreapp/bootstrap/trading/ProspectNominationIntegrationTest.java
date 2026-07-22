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
 * TICKET-118 follow-up — the "Follower refers a prospect, lands in their Master's inbox, Master
 * sends a real invitation" flow. Same real-HTTP-round-trip style as {@code
 * CopyRelationshipIntegrationTest}/{@code InvitationIntegrationTest}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProspectNominationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private record HttpResult(int status, Map<String, Object> body) {}

  private record ListHttpResult(int status, List<Map<String, Object>> body) {}

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
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  private record NewUser(UUID userId, String email, String accessToken) {}

  private NewUser newUser(String displayName, String... roles) {
    String email = "nom-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", displayName, null, null, null, "US");
    for (String role : roles) {
      grantRole(userId, role);
    }
    return new NewUser(userId, email, loginAs(email));
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

  private record MasterFixture(NewUser user, UUID masterProfileId) {}

  private MasterFixture newMaster(String displayName) {
    NewUser master = newUser(displayName, "MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name)
        VALUES (?, ?, ?, ?)
        """,
        masterProfileId,
        master.userId(),
        brokerAccountId,
        displayName);
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

  /** {@code chk_exactly_one_origin} requires a real {@code invitations} row to reference. */
  private UUID insertInvitationRow(UUID masterProfileId, UUID createdByUserId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, now() + interval '7 days')
        """,
        id,
        masterProfileId,
        "seed-" + id + "@example.com",
        "hash-" + id,
        createdByUserId);
    return id;
  }

  /**
   * Seeds a real copy_relationships row linking `follower` to `master` — "my master" resolution.
   */
  private void linkFollowerToMaster(NewUser follower, MasterFixture master) {
    UUID followerBrokerAccountId = insertBrokerAccount(follower.userId());
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitationRow(master.masterProfileId(), master.user().userId());
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_invitation_id)
        VALUES (?, (SELECT primary_broker_account_id FROM master_profiles WHERE id = ?), ?, ?, ?, ?,
                'ACTIVE', 20.00, 'STRIPE_INVOICE', ?)
        """,
        master.masterProfileId(),
        master.masterProfileId(),
        follower.userId(),
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        invitationId);
  }

  // ==================== nominate ====================

  @Test
  void nominate_landsInTheFollowersOwnMastersInbox() {
    MasterFixture master = newMaster("Nominate Master A");
    NewUser follower = newUser("Referring Follower", "FOLLOWER");
    linkFollowerToMaster(follower, master);

    HttpResult nominate =
        request(
            "POST",
            "/api/v1/prospect-nominations",
            Map.of("prospect_email", "prospect-a@example.com"),
            follower.accessToken());
    assertThat(nominate.status()).isEqualTo(200);

    ListHttpResult inbox =
        requestList(
            "/api/v1/master/prospect-nominations?status=PENDING", master.user().accessToken());
    assertThat(inbox.status()).isEqualTo(200);
    assertThat(inbox.body()).hasSize(1);
    assertThat(inbox.body().get(0).get("prospect_email")).isEqualTo("prospect-a@example.com");
    assertThat(inbox.body().get(0).get("nominated_by_display_name"))
        .isEqualTo("Referring Follower");
  }

  @Test
  void nominate_doesNotLeakIntoAnUnrelatedMastersInbox() {
    MasterFixture master = newMaster("Nominate Master B");
    MasterFixture otherMaster = newMaster("Unrelated Master");
    NewUser follower = newUser("Follower B", "FOLLOWER");
    linkFollowerToMaster(follower, master);

    HttpResult nominate =
        request(
            "POST",
            "/api/v1/prospect-nominations",
            Map.of("prospect_email", "prospect-b@example.com"),
            follower.accessToken());
    assertThat(nominate.status()).isEqualTo(200);

    ListHttpResult otherInbox =
        requestList(
            "/api/v1/master/prospect-nominations?status=PENDING", otherMaster.user().accessToken());
    assertThat(otherInbox.status()).isEqualTo(200);
    assertThat(otherInbox.body()).isEmpty();
  }

  @Test
  void nominate_withNoMasterYet_isRejectedWith409() {
    NewUser lonelyFollower = newUser("Lonely Follower", "FOLLOWER");

    HttpResult nominate =
        request(
            "POST",
            "/api/v1/prospect-nominations",
            Map.of("prospect_email", "nobody@example.com"),
            lonelyFollower.accessToken());
    assertThat(nominate.status()).isEqualTo(409);
  }

  @Test
  void nominate_dispatchesARealNotificationToTheMaster() {
    MasterFixture master = newMaster("Notified Master");
    NewUser follower = newUser("Notifying Follower", "FOLLOWER");
    linkFollowerToMaster(follower, master);

    request(
        "POST",
        "/api/v1/prospect-nominations",
        Map.of("prospect_email", "notify-check@example.com"),
        follower.accessToken());

    Integer logRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND event_type = 'prospect_nomination.received'",
            Integer.class,
            master.user().userId());
    assertThat(logRows).isGreaterThan(0);
  }

  // ==================== RBAC ====================

  @Test
  void nominate_byAMasterCaller_isForbidden() {
    MasterFixture master = newMaster("Not A Follower");
    HttpResult nominate =
        request(
            "POST",
            "/api/v1/prospect-nominations",
            Map.of("prospect_email", "irrelevant@example.com"),
            master.user().accessToken());
    assertThat(nominate.status()).isEqualTo(403);
  }

  @Test
  void listInbox_byAFollowerCaller_isForbidden() {
    NewUser follower = newUser("Not A Master", "FOLLOWER");
    ListHttpResult inbox =
        requestList("/api/v1/master/prospect-nominations", follower.accessToken());
    assertThat(inbox.status()).isEqualTo(403);
  }

  // ==================== dismiss ====================

  @Test
  void dismiss_removesItFromThePendingList() {
    MasterFixture master = newMaster("Dismiss Master");
    NewUser follower = newUser("Dismiss Follower", "FOLLOWER");
    linkFollowerToMaster(follower, master);
    request(
        "POST",
        "/api/v1/prospect-nominations",
        Map.of("prospect_email", "dismiss-me@example.com"),
        follower.accessToken());
    String nominationId =
        (String)
            requestList(
                    "/api/v1/master/prospect-nominations?status=PENDING",
                    master.user().accessToken())
                .body()
                .get(0)
                .get("id");

    HttpResult dismiss =
        request(
            "POST",
            "/api/v1/master/prospect-nominations/" + nominationId + "/dismiss",
            null,
            master.user().accessToken());
    assertThat(dismiss.status()).isEqualTo(204);

    ListHttpResult pendingAfter =
        requestList(
            "/api/v1/master/prospect-nominations?status=PENDING", master.user().accessToken());
    assertThat(pendingAfter.body()).isEmpty();
  }

  @Test
  void dismiss_anotherMastersNomination_isNotFound() {
    MasterFixture owner = newMaster("Owner Master");
    MasterFixture attacker = newMaster("Attacker Master");
    NewUser follower = newUser("Owned Follower", "FOLLOWER");
    linkFollowerToMaster(follower, owner);
    request(
        "POST",
        "/api/v1/prospect-nominations",
        Map.of("prospect_email", "protected@example.com"),
        follower.accessToken());
    String nominationId =
        (String)
            requestList(
                    "/api/v1/master/prospect-nominations?status=PENDING",
                    owner.user().accessToken())
                .body()
                .get(0)
                .get("id");

    HttpResult dismiss =
        request(
            "POST",
            "/api/v1/master/prospect-nominations/" + nominationId + "/dismiss",
            null,
            attacker.user().accessToken());
    assertThat(dismiss.status()).isEqualTo(404);
  }

  // ==================== send invite (mark-invited) ====================

  @Test
  void sendInvite_createsARealInvitationAndMarksTheNominationInvited() {
    MasterFixture master = newMaster("Invite-Sending Master");
    NewUser follower = newUser("Invite Follower", "FOLLOWER");
    linkFollowerToMaster(follower, master);
    request(
        "POST",
        "/api/v1/prospect-nominations",
        Map.of("prospect_email", "invite-me@example.com"),
        follower.accessToken());
    String nominationId =
        (String)
            requestList(
                    "/api/v1/master/prospect-nominations?status=PENDING",
                    master.user().accessToken())
                .body()
                .get(0)
                .get("id");

    HttpResult createInvitation =
        request(
            "POST",
            "/api/v1/master/invitations",
            Map.of("invited_email", "invite-me@example.com"),
            master.user().accessToken());
    assertThat(createInvitation.status()).isEqualTo(200);
    String invitationId = (String) createInvitation.body().get("id");

    HttpResult markInvited =
        request(
            "POST",
            "/api/v1/master/prospect-nominations/" + nominationId + "/mark-invited",
            Map.of("invitation_id", invitationId),
            master.user().accessToken());
    assertThat(markInvited.status()).isEqualTo(204);

    UUID nominationUuid = UUID.fromString(nominationId);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM prospect_nominations WHERE id = ?", String.class, nominationUuid);
    assertThat(storedStatus).isEqualTo("INVITED");
    String storedInvitationId =
        jdbcTemplate.queryForObject(
            "SELECT invitation_id FROM prospect_nominations WHERE id = ?",
            String.class,
            nominationUuid);
    assertThat(storedInvitationId).isEqualTo(invitationId);

    ListHttpResult pendingAfter =
        requestList(
            "/api/v1/master/prospect-nominations?status=PENDING", master.user().accessToken());
    assertThat(pendingAfter.body()).isEmpty();
  }
}
