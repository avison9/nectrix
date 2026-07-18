package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.billing.repository.SubscriptionRepository;
import com.nectrix.coreapp.invitations.service.BrokerAccountLimitExceededException;
import com.nectrix.coreapp.invitations.service.MtLinkingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TICKET-114 AC4 — master-slot/follower-slot enforcement at the broker-linking insertion point,
 * scoped to Individual-mode callers only. A real Master/Follower (holding that role) is never
 * checked against these limits at all — the corrected, second-pass understanding of this ticket
 * ("is for when a user wants to use individual copy and not the master-follower").
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IndividualModeCapabilityIntegrationTest {

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private MtLinkingService mtLinkingService;
  @Autowired private SubscriptionRepository subscriptionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID newUser() {
    String email = "cap-" + UUID.randomUUID() + "@example.com";
    UUID userId =
        userProvisioningApi.createUser(
            email, "correct horse battery staple", "Test User", null, null, null, "US");
    userProvisioningApi.grantRole(userId, "USER");
    return userId;
  }

  private MtLinkingService.LinkRequest request(String connectionRole) {
    return new MtLinkingService.LinkRequest(
        "login-" + UUID.randomUUID(),
        "password",
        "Demo-Server",
        true,
        "Label",
        connectionRole,
        null);
  }

  private int brokerAccountCount(UUID userId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM broker_accounts WHERE user_id = ?", Long.class, userId);
    return count.intValue();
  }

  @Test
  void individualMode_fourthMasterOnlyLink_rejectedOnceThreeSlotLimitIsReached() {
    UUID userId = newUser();
    subscriptionRepository.insert(
        userId,
        "INDIVIDUAL",
        "ACTIVE",
        Instant.now().minus(1, ChronoUnit.DAYS),
        Instant.now().plus(29, ChronoUnit.DAYS),
        "cus_test",
        "sub_test_" + UUID.randomUUID());
    List<String> callerRoles = List.of("USER");

    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));
    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));
    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));

    assertThatThrownBy(() -> mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY")))
        .isInstanceOf(BrokerAccountLimitExceededException.class);
    assertThat(brokerAccountCount(userId)).isEqualTo(3);
  }

  @Test
  void individualMode_noSubscription_secondLinkRejectedImmediately() {
    UUID userId = newUser();
    List<String> callerRoles = List.of("USER");

    assertThatThrownBy(() -> mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY")))
        .isInstanceOf(BrokerAccountLimitExceededException.class);
    assertThat(brokerAccountCount(userId)).isZero();
  }

  @Test
  void realMaster_unlimitedLinking_neverCheckedAgainstAnyPlan() {
    UUID userId = newUser();
    // Deliberately no subscription row -- a real Master isn't subscription-gated at all.
    List<String> callerRoles = List.of("USER", "MASTER");

    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));
    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));
    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));
    mtLinkingService.linkMt5(userId, callerRoles, request("MASTER_ONLY"));

    assertThat(brokerAccountCount(userId)).isEqualTo(4);
  }
}
