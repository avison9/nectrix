package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.MoneyManagementProfileRepository;
import com.nectrix.coreapp.trading.repository.RiskProfileRepository;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Admin manual follower-master linking (#421) — a SUPER_ADMIN/ADMIN can create a real {@code
 * copy_relationships} row directly, without the Master sending an invite or the Follower
 * requesting one. {@code originating_admin_action=true} satisfies {@code chk_exactly_one_origin}'s
 * 4th option (036-admin-manual-copy-link.sql), same widening precedent {@code
 * IndividualCopySetupService}'s own {@code originating_individual_setup} established.
 *
 * <p>Status derivation deliberately mirrors {@link InvitationCopySetupService}'s own logic, not a
 * blanket {@code ACTIVE}: a {@code BROKER_PARTNERSHIP} master's relationship normally can't reach
 * {@code ACTIVE} without a real signed fee-agreement document ({@code
 * CopyRelationshipService#signAgreement} uploads one, and nothing downstream — broker-fee-report
 * generation — checks for its existence before charging), so this bypass only ever skips the
 * risk-ack formality (no document behind it, and {@code SettlementSchedulerService} already
 * tolerates a null {@code risk_ack_at} by falling back to {@code created_at}), never the agreement
 * step. A {@code STRIPE_INVOICE} master's relationship has no such document requirement, so it goes
 * straight to {@code ACTIVE}.
 */
@Service
public class AdminCopyLinkService {

  private final MasterProfileLookupApi masterProfileLookupApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final MoneyManagementProfileRepository moneyManagementProfileRepository;
  private final RiskProfileRepository riskProfileRepository;
  private final CopyRelationshipRepository copyRelationshipRepository;

  public AdminCopyLinkService(
      MasterProfileLookupApi masterProfileLookupApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      MoneyManagementProfileRepository moneyManagementProfileRepository,
      RiskProfileRepository riskProfileRepository,
      CopyRelationshipRepository copyRelationshipRepository) {
    this.masterProfileLookupApi = masterProfileLookupApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.moneyManagementProfileRepository = moneyManagementProfileRepository;
    this.riskProfileRepository = riskProfileRepository;
    this.copyRelationshipRepository = copyRelationshipRepository;
  }

  public CopyRelationship linkFollowerToMaster(
      UUID followerUserId, UUID masterUserId, UUID followerBrokerAccountId) {
    MasterProfileSummaryView master =
        masterProfileLookupApi
            .findByUserId(masterUserId)
            .orElseThrow(NoSuchMasterProfileException::new);
    BrokerAccountView followerAccount = lookupOwned(followerUserId, followerBrokerAccountId);
    if (master.primaryBrokerAccountId().equals(followerAccount.id())) {
      throw new SameBrokerAccountException();
    }
    if (copyRelationshipRepository.existsActiveOrPendingForPair(
        master.primaryBrokerAccountId(), followerAccount.id())) {
      throw new DuplicateCopyRelationshipException();
    }

    UUID moneyManagementProfileId =
        moneyManagementProfileRepository.insert(
            "MULTIPLIER", null, BigDecimal.ONE, null, null, "DOWN");
    UUID riskProfileId = riskProfileRepository.insert(null, null, null, null, null);

    String status =
        "BROKER_PARTNERSHIP".equals(master.feeCollectionMethod()) ? "PENDING_AGREEMENT" : "ACTIVE";
    UUID copyRelationshipId =
        copyRelationshipRepository.insertAdminLinked(
            master.id(),
            master.primaryBrokerAccountId(),
            followerUserId,
            followerAccount.id(),
            moneyManagementProfileId,
            riskProfileId,
            master.performanceFeePercent(),
            master.feeCollectionMethod(),
            status);
    return copyRelationshipRepository
        .findById(copyRelationshipId)
        .orElseThrow(CopyRelationshipNotFoundException::new);
  }

  private BrokerAccountView lookupOwned(UUID callerUserId, UUID brokerAccountId) {
    BrokerAccountView account;
    try {
      account = brokerAccountLookupApi.getBrokerAccount(brokerAccountId);
    } catch (NoSuchElementException e) {
      throw new BrokerAccountNotOwnedException();
    }
    if (!account.userId().equals(callerUserId)) {
      throw new BrokerAccountNotOwnedException();
    }
    return account;
  }
}
