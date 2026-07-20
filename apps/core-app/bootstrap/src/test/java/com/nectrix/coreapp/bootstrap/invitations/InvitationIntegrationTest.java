package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.notifications.service.EmailSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import redis.clients.jedis.UnifiedJedis;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-118 — end-to-end HTTP round trips against a real DB, same style as {@code
 * CopyRelationshipIntegrationTest}/{@code RbacIntegrationTest}. {@link EmailSender} is
 * {@code @MockitoBean}-replaced (real SES is unconfigured in dev/test, same posture {@code
 * NotificationDispatchServiceIntegrationTest} already established) — its captured {@code body}
 * argument is this test's only way to recover the raw invitation token, since the real API never
 * returns it anywhere (by design).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationIntegrationTest {

  private static final Pattern TOKEN_PATTERN = Pattern.compile("[?&]token=([^&\\s]+)");

  @LocalServerPort private int port;

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private UnifiedJedis jedis;

  @MockitoBean private EmailSender emailSender;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  /**
   * The by-token/accept-invite rate limiter is keyed by caller IP with no per-test isolation —
   * every test in this class hits it from the same loopback address, so a full reset before each
   * test is required; otherwise an earlier test's calls silently eat into a later test's budget (or
   * vice versa), making both the dedicated rate-limit test and the ordinary error-case tests that
   * expect a real 400/200 flaky depending on JUnit5's (unspecified) method execution order.
   */
  @BeforeEach
  void resetRateLimitBuckets() {
    jedis.del(
        "ratelimit:invitations:by-token:127.0.0.1",
        "ratelimit:invitations:accept-invite:127.0.0.1");
  }

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
    assertThat(login.status()).isEqualTo(200);
    return (String) login.body().get("access_token");
  }

  private record NewUser(UUID userId, String email, String accessToken) {}

  private NewUser newUser(String... roles) {
    String email = "invite-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
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

  private MasterFixture newMaster(String feeCollectionMethod) {
    NewUser master = newUser("MASTER");
    UUID brokerAccountId = insertBrokerAccount(master.userId());
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO master_profiles
          (id, user_id, primary_broker_account_id, display_name, performance_fee_percent, fee_collection_method)
        VALUES (?, ?, ?, 'Test Master', 20.00, ?)
        """,
        masterProfileId,
        master.userId(),
        brokerAccountId,
        feeCollectionMethod);
    return new MasterFixture(master, masterProfileId);
  }

  private String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Seeds an {@code invitations} row directly, for states the public API can't itself produce. */
  private UUID seedInvitation(
      UUID masterProfileId,
      UUID createdByUserId,
      String status,
      String rawToken,
      Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        masterProfileId,
        "seeded-" + id + "@example.com",
        hashToken(rawToken),
        status,
        createdByUserId,
        Timestamp.from(expiresAt));
    return id;
  }

  private String createInvitationAndCaptureToken(MasterFixture master, String invitedEmail) {
    when(emailSender.send(any(), any(), any())).thenReturn(true);
    HttpResult create =
        request(
            "POST",
            "/api/v1/master/invitations",
            Map.of("invited_email", invitedEmail),
            master.user().accessToken());
    assertThat(create.status()).isEqualTo(200);

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailSender).send(anyString(), anyString(), bodyCaptor.capture());
    // Tests that create more than one invitation (e.g. the multi-master AC) call this helper
    // more than once — reset so the NEXT call's own verify() only sees its own invocation.
    org.mockito.Mockito.clearInvocations(emailSender);
    Matcher matcher = TOKEN_PATTERN.matcher(bodyCaptor.getValue());
    assertThat(matcher.find()).as("email body should embed the accept-invite link").isTrue();
    return matcher.group(1);
  }

  // ==================== token hashing / no leak ====================

  @Test
  void create_neverPersistsTheRawToken_onlyItsHash() {
    MasterFixture master = newMaster("BROKER_PARTNERSHIP");
    String invitedEmail = "hash-check-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);

    List<String> storedHashes =
        jdbcTemplate.queryForList(
            "SELECT token_hash FROM invitations WHERE invited_email = ?",
            String.class,
            invitedEmail);
    assertThat(storedHashes).containsExactly(hashToken(rawToken));
    assertThat(storedHashes.get(0)).isNotEqualTo(rawToken);
  }

  // ==================== accept-invite: brand-new email ====================

  @Test
  void acceptInvite_forBrandNewEmail_createsExactlyOneUserAndMarksAccepted() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "brand-new-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);

    HttpResult preview = request("GET", "/api/v1/invitations/by-token/" + rawToken, null, null);
    assertThat(preview.status()).isEqualTo(200);
    assertThat(preview.body().get("invited_email")).isEqualTo(invitedEmail);
    assertThat(preview.body().get("master_display_name")).isEqualTo("Test Master");

    HttpResult accept =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawToken, "password", "correct horse battery staple"),
            null);
    assertThat(accept.status()).isEqualTo(200);
    assertThat(accept.body().get("access_token")).isNotNull();

    Integer userCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, invitedEmail);
    assertThat(userCount).isEqualTo(1);

    String invitationStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM invitations WHERE invited_email = ?", String.class, invitedEmail);
    assertThat(invitationStatus).isEqualTo("ACCEPTED");

    List<String> roles =
        jdbcTemplate.queryForList(
            """
            SELECT r.name FROM roles r
            JOIN user_roles ur ON ur.role_id = r.id
            JOIN users u ON u.id = ur.user_id
            WHERE u.email = ?
            """,
            String.class,
            invitedEmail);
    assertThat(roles).contains("FOLLOWER");
  }

  // ==================== accept-invite: already-registered email (multi-master AC)
  // ====================

  @Test
  void acceptInvite_forAlreadyRegisteredEmail_doesNotCreateASecondUserRow() {
    MasterFixture masterA = newMaster("STRIPE_INVOICE");
    MasterFixture masterB = newMaster("STRIPE_INVOICE");
    NewUser existing = newUser("FOLLOWER");

    String rawTokenA = createInvitationAndCaptureToken(masterA, existing.email());
    HttpResult acceptA =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawTokenA, "password", "irrelevant-since-account-exists"),
            null);
    assertThat(acceptA.status()).isEqualTo(200);

    String rawTokenB = createInvitationAndCaptureToken(masterB, existing.email());
    HttpResult acceptB =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawTokenB, "password", "irrelevant-since-account-exists"),
            null);
    assertThat(acceptB.status()).isEqualTo(200);

    Integer userCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, existing.email());
    assertThat(userCount).isEqualTo(1);
  }

  // ==================== invalid/expired/revoked/already-accepted — same generic error
  // ====================

  @Test
  void byToken_forANonExistentToken_returnsGenericBadRequest() {
    HttpResult response =
        request("GET", "/api/v1/invitations/by-token/definitely-not-a-real-token", null, null);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_or_expired_invitation");
  }

  @Test
  void byToken_forAnExpiredInvitation_returnsGenericBadRequestAndFlipsStatus() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String rawToken = "expired-" + UUID.randomUUID();
    UUID invitationId =
        seedInvitation(
            master.masterProfileId(),
            master.user().userId(),
            "PENDING",
            rawToken,
            Instant.now().minus(1, ChronoUnit.DAYS));

    HttpResult response = request("GET", "/api/v1/invitations/by-token/" + rawToken, null, null);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_or_expired_invitation");

    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
    assertThat(storedStatus).isEqualTo("EXPIRED");
  }

  @Test
  void byToken_forARevokedInvitation_returnsGenericBadRequest() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String rawToken = "revoked-" + UUID.randomUUID();
    seedInvitation(
        master.masterProfileId(),
        master.user().userId(),
        "REVOKED",
        rawToken,
        Instant.now().plus(7, ChronoUnit.DAYS));

    HttpResult response = request("GET", "/api/v1/invitations/by-token/" + rawToken, null, null);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_or_expired_invitation");
  }

  @Test
  void byToken_forAnAlreadyAcceptedInvitation_returnsGenericBadRequest() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String rawToken = "accepted-" + UUID.randomUUID();
    seedInvitation(
        master.masterProfileId(),
        master.user().userId(),
        "ACCEPTED",
        rawToken,
        Instant.now().plus(7, ChronoUnit.DAYS));

    HttpResult response = request("GET", "/api/v1/invitations/by-token/" + rawToken, null, null);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().get("error")).isEqualTo("invalid_or_expired_invitation");
  }

  // ==================== revoke ====================

  @Test
  void revoke_aPendingInvitation_makesItsLinkStopWorking() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "to-revoke-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult revoke =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/revoke",
            null,
            master.user().accessToken());
    assertThat(revoke.status()).isEqualTo(204);

    HttpResult afterRevoke = request("GET", "/api/v1/invitations/by-token/" + rawToken, null, null);
    assertThat(afterRevoke.status()).isEqualTo(400);
  }

  @Test
  void revoke_anotherMastersInvitation_isNotFound() {
    MasterFixture owner = newMaster("STRIPE_INVOICE");
    MasterFixture attacker = newMaster("STRIPE_INVOICE");
    String invitedEmail = "owned-by-someone-else-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(owner, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult revoke =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/revoke",
            null,
            attacker.user().accessToken());
    assertThat(revoke.status()).isEqualTo(404);

    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
    assertThat(storedStatus).isEqualTo("PENDING");
  }

  // ==================== resend ====================

  @Test
  void resend_aPendingInvitation_rotatesTheTokenAndBumpsResendCount() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "to-resend-" + UUID.randomUUID() + "@example.com";
    String originalRawToken = createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    when(emailSender.send(any(), any(), any())).thenReturn(true);
    HttpResult resend =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/resend",
            null,
            master.user().accessToken());
    assertThat(resend.status()).isEqualTo(200);
    assertThat(resend.body().get("resend_count")).isEqualTo(1);

    // createInvitationAndCaptureToken already clears invocations after the initial create, so
    // this only sees the resend's own send() call.
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailSender).send(anyString(), anyString(), bodyCaptor.capture());
    Matcher matcher = TOKEN_PATTERN.matcher(bodyCaptor.getValue());
    assertThat(matcher.find()).isTrue();
    String newRawToken = matcher.group(1);
    assertThat(newRawToken).isNotEqualTo(originalRawToken);

    // The old token stopped working; the new one is live.
    HttpResult oldTokenLookup =
        request("GET", "/api/v1/invitations/by-token/" + originalRawToken, null, null);
    assertThat(oldTokenLookup.status()).isEqualTo(400);
    HttpResult newTokenLookup =
        request("GET", "/api/v1/invitations/by-token/" + newRawToken, null, null);
    assertThat(newTokenLookup.status()).isEqualTo(200);
  }

  @Test
  void resend_anExpiredInvitation_revivesItBackToPending() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    UUID invitationId =
        seedInvitation(
            master.masterProfileId(),
            master.user().userId(),
            "EXPIRED",
            "expired-to-resend-" + UUID.randomUUID(),
            Instant.now().minus(1, ChronoUnit.DAYS));

    when(emailSender.send(any(), any(), any())).thenReturn(true);
    HttpResult resend =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/resend",
            null,
            master.user().accessToken());
    assertThat(resend.status()).isEqualTo(200);
    assertThat(resend.body().get("status")).isEqualTo("PENDING");

    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
    assertThat(storedStatus).isEqualTo("PENDING");
  }

  @Test
  void resend_anAcceptedInvitation_isRejected() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    UUID invitationId =
        seedInvitation(
            master.masterProfileId(),
            master.user().userId(),
            "ACCEPTED",
            "accepted-to-resend-" + UUID.randomUUID(),
            Instant.now().plus(7, ChronoUnit.DAYS));

    HttpResult resend =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/resend",
            null,
            master.user().accessToken());
    assertThat(resend.status()).isEqualTo(409);
    assertThat(resend.body().get("error")).isEqualTo("invitation_not_resendable");
  }

  @Test
  void resend_aRevokedInvitation_isRejected() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    UUID invitationId =
        seedInvitation(
            master.masterProfileId(),
            master.user().userId(),
            "REVOKED",
            "revoked-to-resend-" + UUID.randomUUID(),
            Instant.now().plus(7, ChronoUnit.DAYS));

    HttpResult resend =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/resend",
            null,
            master.user().accessToken());
    assertThat(resend.status()).isEqualTo(409);
    assertThat(resend.body().get("error")).isEqualTo("invitation_not_resendable");
  }

  @Test
  void resend_anotherMastersInvitation_isNotFound() {
    MasterFixture owner = newMaster("STRIPE_INVOICE");
    MasterFixture attacker = newMaster("STRIPE_INVOICE");
    String invitedEmail = "resend-owned-by-someone-else-" + UUID.randomUUID() + "@example.com";
    createInvitationAndCaptureToken(owner, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult resend =
        request(
            "POST",
            "/api/v1/master/invitations/" + invitationId + "/resend",
            null,
            attacker.user().accessToken());
    assertThat(resend.status()).isEqualTo(404);
  }

  @Test
  void resend_repeatedlyPastTheLimit_eventuallyGetsRateLimited() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "resend-rate-limit-" + UUID.randomUUID() + "@example.com";
    createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));
    when(emailSender.send(any(), any(), any())).thenReturn(true);

    List<Integer> statuses =
        java.util.stream.IntStream.range(0, 8)
            .mapToObj(
                i ->
                    request(
                            "POST",
                            "/api/v1/master/invitations/" + invitationId + "/resend",
                            null,
                            master.user().accessToken())
                        .status())
            .toList();
    assertThat(statuses).contains(429);
  }

  // ==================== pending-invitation: organic (never-invited) user ====================

  @Test
  void pendingInvitation_forAnOrganicUser_returnsNoContentRatherThanErroring() {
    // Regression — an organic account's created_via_invitation_id is NULL (the overwhelmingly
    // common case, not an edge case), which used to NPE this endpoint entirely (see
    // UserInvitationLookupRepository's own Javadoc).
    NewUser organic = newUser("FOLLOWER");
    HttpResult pending =
        request("GET", "/api/v1/users/me/pending-invitation", null, organic.accessToken());
    assertThat(pending.status()).isEqualTo(204);
  }

  // ==================== RBAC ====================

  @Test
  void create_byAFollowerCaller_isForbidden() {
    NewUser follower = newUser("FOLLOWER");
    HttpResult response =
        request(
            "POST",
            "/api/v1/master/invitations",
            Map.of("invited_email", "irrelevant@example.com"),
            follower.accessToken());
    assertThat(response.status()).isEqualTo(403);
  }

  // ==================== from-invitation: full acceptance -> copy-relationship chain
  // ====================

  @Test
  void
      fromInvitation_afterAccepting_createsCopyRelationshipWithOriginatingInvitationId_brokerPartnership() {
    MasterFixture master = newMaster("BROKER_PARTNERSHIP");
    String invitedEmail = "chain-bp-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult accept =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawToken, "password", "correct horse battery staple"),
            null);
    assertThat(accept.status()).isEqualTo(200);
    String followerAccessToken = (String) accept.body().get("access_token");

    UUID followerUserId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", String.class, invitedEmail));
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);

    HttpResult pending =
        request("GET", "/api/v1/users/me/pending-invitation", null, followerAccessToken);
    assertThat(pending.status()).isEqualTo(200);
    assertThat(pending.body().get("invitation_id")).isEqualTo(invitationId.toString());

    HttpResult fromInvitation =
        request(
            "POST",
            "/api/v1/copy-relationships/from-invitation",
            Map.of(
                "invitation_id", invitationId.toString(),
                "follower_broker_account_id", followerBrokerAccountId.toString()),
            followerAccessToken);
    assertThat(fromInvitation.status()).isEqualTo(200);
    assertThat(fromInvitation.body().get("status")).isEqualTo("PENDING_AGREEMENT");
    assertThat(fromInvitation.body().get("originating_invitation_id"))
        .isEqualTo(invitationId.toString());

    Integer relationshipCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM copy_relationships WHERE originating_invitation_id = ?",
            Integer.class,
            invitationId);
    assertThat(relationshipCount).isEqualTo(1);

    // No longer "pending" once actioned.
    HttpResult pendingAfter =
        request("GET", "/api/v1/users/me/pending-invitation", null, followerAccessToken);
    assertThat(pendingAfter.status()).isEqualTo(204);
  }

  @Test
  void fromInvitation_withStripeInvoiceFeeMethod_startsAtPendingRiskAck() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "chain-si-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult accept =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawToken, "password", "correct horse battery staple"),
            null);
    String followerAccessToken = (String) accept.body().get("access_token");
    UUID followerUserId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", String.class, invitedEmail));
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);

    HttpResult fromInvitation =
        request(
            "POST",
            "/api/v1/copy-relationships/from-invitation",
            Map.of(
                "invitation_id", invitationId.toString(),
                "follower_broker_account_id", followerBrokerAccountId.toString()),
            followerAccessToken);
    assertThat(fromInvitation.status()).isEqualTo(200);
    assertThat(fromInvitation.body().get("status")).isEqualTo("PENDING_RISK_ACK");
  }

  @Test
  void fromInvitation_calledTwiceForTheSameInvitation_secondCallIsRejected() {
    MasterFixture master = newMaster("STRIPE_INVOICE");
    String invitedEmail = "chain-dup-" + UUID.randomUUID() + "@example.com";
    String rawToken = createInvitationAndCaptureToken(master, invitedEmail);
    UUID invitationId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM invitations WHERE invited_email = ?", String.class, invitedEmail));

    HttpResult accept =
        request(
            "POST",
            "/api/v1/auth/accept-invite",
            Map.of("token", rawToken, "password", "correct horse battery staple"),
            null);
    String followerAccessToken = (String) accept.body().get("access_token");
    UUID followerUserId =
        UUID.fromString(
            jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", String.class, invitedEmail));
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);

    Map<String, Object> body =
        Map.of(
            "invitation_id", invitationId.toString(),
            "follower_broker_account_id", followerBrokerAccountId.toString());
    HttpResult first =
        request("POST", "/api/v1/copy-relationships/from-invitation", body, followerAccessToken);
    assertThat(first.status()).isEqualTo(200);

    HttpResult second =
        request("POST", "/api/v1/copy-relationships/from-invitation", body, followerAccessToken);
    assertThat(second.status()).isEqualTo(409);

    Integer relationshipCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM copy_relationships WHERE originating_invitation_id = ?",
            Integer.class,
            invitationId);
    assertThat(relationshipCount).isEqualTo(1);
  }

  // ==================== rate limiting ====================

  @Test
  void byToken_repeatedRapidGuesses_eventuallyGetsRateLimited() {
    List<Integer> statuses =
        java.util.stream.IntStream.range(0, 15)
            .mapToObj(
                i ->
                    request("GET", "/api/v1/invitations/by-token/rate-limit-probe-" + i, null, null)
                        .status())
            .toList();
    assertThat(statuses).contains(429);
  }
}
