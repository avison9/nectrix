package com.nectrix.coreapp.bootstrap.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository.SnapshotCandidate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bugfix — real-DB coverage of {@link BrokerAccountRepository#findSnapshotCandidates()}, the query
 * {@code AccountSnapshotSchedulerJob} relies on to fix analytics' equity curve being sparse/stale.
 * Covers all three UNION legs (a brand-new Master with zero followers yet; either side of an
 * ACTIVE/PAUSED copy_relationships row) and confirms a non-CONNECTED account is excluded.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSnapshotCandidatesIntegrationTest {

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;
  @Autowired private BrokerAccountRepository brokerAccountRepository;

  private UUID createUser(String email) {
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  private UUID insertBrokerAccount(UUID userId, String connectionStatus) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Test Label', false, 'USD', ?, ?, ?)
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion(),
        connectionStatus);
    return accountId;
  }

  private UUID insertMasterProfile(UUID userId, UUID primaryBrokerAccountId) {
    UUID masterProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name) VALUES (?, ?, ?, 'Test Master')",
        masterProfileId,
        userId,
        primaryBrokerAccountId);
    return masterProfileId;
  }

  private void insertCopyRelationship(
      UUID masterProfileId,
      UUID masterBrokerAccountId,
      UUID followerUserId,
      UUID followerBrokerAccountId,
      String status) {
    UUID mmProfileId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        mmProfileId);
    UUID riskProfileId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", riskProfileId);
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, originating_admin_action)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 20.00, 'STRIPE_INVOICE', true)
        """,
        UUID.randomUUID(),
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        status);
  }

  @Test
  void newMasterWithNoFollowersYet_stillIncludesTheirPrimaryBrokerAccount() {
    UUID masterUserId = createUser("snapshot-master-" + UUID.randomUUID() + "@example.com");
    UUID masterAccount = insertBrokerAccount(masterUserId, "CONNECTED");
    insertMasterProfile(masterUserId, masterAccount);

    List<SnapshotCandidate> candidates = brokerAccountRepository.findSnapshotCandidates();

    assertThat(candidates).anySatisfy(c -> assertThat(c.id()).isEqualTo(masterAccount));
  }

  @Test
  void activeCopyRelationship_includesBothMasterAndFollowerBrokerAccounts() {
    UUID masterUserId = createUser("snapshot-master-" + UUID.randomUUID() + "@example.com");
    UUID masterAccount = insertBrokerAccount(masterUserId, "CONNECTED");
    UUID masterProfileId = insertMasterProfile(masterUserId, masterAccount);

    UUID followerUserId = createUser("snapshot-follower-" + UUID.randomUUID() + "@example.com");
    UUID followerAccount = insertBrokerAccount(followerUserId, "CONNECTED");
    insertCopyRelationship(
        masterProfileId, masterAccount, followerUserId, followerAccount, "ACTIVE");

    List<SnapshotCandidate> candidates = brokerAccountRepository.findSnapshotCandidates();

    assertThat(candidates).anySatisfy(c -> assertThat(c.id()).isEqualTo(masterAccount));
    assertThat(candidates).anySatisfy(c -> assertThat(c.id()).isEqualTo(followerAccount));
  }

  @Test
  void stoppedRelationship_followerBrokerAccountExcluded() {
    UUID masterUserId = createUser("snapshot-master-" + UUID.randomUUID() + "@example.com");
    UUID masterAccount = insertBrokerAccount(masterUserId, "CONNECTED");
    UUID masterProfileId = insertMasterProfile(masterUserId, masterAccount);

    UUID followerUserId = createUser("snapshot-follower-" + UUID.randomUUID() + "@example.com");
    UUID followerAccount = insertBrokerAccount(followerUserId, "CONNECTED");
    insertCopyRelationship(
        masterProfileId, masterAccount, followerUserId, followerAccount, "STOPPED");

    List<SnapshotCandidate> candidates = brokerAccountRepository.findSnapshotCandidates();

    assertThat(candidates).noneSatisfy(c -> assertThat(c.id()).isEqualTo(followerAccount));
  }

  @Test
  void nonConnectedAccount_excludedEvenIfOtherwiseEligible() {
    UUID masterUserId = createUser("snapshot-master-" + UUID.randomUUID() + "@example.com");
    UUID masterAccount = insertBrokerAccount(masterUserId, "DISCONNECTED");
    insertMasterProfile(masterUserId, masterAccount);

    List<SnapshotCandidate> candidates = brokerAccountRepository.findSnapshotCandidates();

    assertThat(candidates).noneSatisfy(c -> assertThat(c.id()).isEqualTo(masterAccount));
  }
}
