package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import com.nectrix.coreapp.social.api.IndividualProfileApi;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.MoneyManagementProfileRepository;
import com.nectrix.coreapp.trading.repository.RiskProfileRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-114 — self-serve "Individual" mode's own copy-trading setup: one "main" broker account
 * (trade source) copying to one or more "slave" accounts, all owned by the same user, broadcast
 * restricted to that one user's own accounts (never another platform user's). Reuses the exact same
 * copy-trading mechanism as real Master->Follower (a {@code copy_relationships} row), just with
 * {@code originating_individual_setup=true} instead of an invitation/follow-request origin, and a
 * private ({@code is_public=false}) {@code master_profiles} row obtained via {@link
 * IndividualProfileApi} rather than the public self-service {@code POST /master-profiles} flow.
 *
 * <p>{@code feeCollectionMethod=STRIPE_INVOICE}/{@code performanceFeePercent=0} — copying your own
 * accounts never owes a performance fee; {@code SettlementCalculationService} already handles a
 * zero percent cleanly (never charges, regardless of profit), so no special-casing is needed
 * downstream in the fee engine for these rows.
 */
@Service
public class IndividualCopySetupService {

  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final IndividualProfileApi individualProfileApi;
  private final MoneyManagementProfileRepository moneyManagementProfileRepository;
  private final RiskProfileRepository riskProfileRepository;
  private final CopyRelationshipRepository copyRelationshipRepository;

  public IndividualCopySetupService(
      BrokerAccountLookupApi brokerAccountLookupApi,
      IndividualProfileApi individualProfileApi,
      MoneyManagementProfileRepository moneyManagementProfileRepository,
      RiskProfileRepository riskProfileRepository,
      CopyRelationshipRepository copyRelationshipRepository) {
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.individualProfileApi = individualProfileApi;
    this.moneyManagementProfileRepository = moneyManagementProfileRepository;
    this.riskProfileRepository = riskProfileRepository;
    this.copyRelationshipRepository = copyRelationshipRepository;
  }

  public CopyRelationship setUp(
      UUID callerUserId,
      List<String> callerRoles,
      UUID mainBrokerAccountId,
      UUID slaveBrokerAccountId) {
    if (callerRoles != null
        && (callerRoles.contains("MASTER") || callerRoles.contains("FOLLOWER"))) {
      throw new IndividualModeRequiredException();
    }
    if (mainBrokerAccountId.equals(slaveBrokerAccountId)) {
      throw new SameBrokerAccountException();
    }
    BrokerAccountView main = lookupOwned(callerUserId, mainBrokerAccountId);
    BrokerAccountView slave = lookupOwned(callerUserId, slaveBrokerAccountId);

    UUID masterProfileId = individualProfileApi.findOrCreatePrivateProfile(callerUserId, main.id());
    UUID moneyManagementProfileId =
        moneyManagementProfileRepository.insert(
            "MULTIPLIER", null, BigDecimal.ONE, null, null, "DOWN");
    UUID riskProfileId = riskProfileRepository.insert(null, null, null, null, null);

    UUID copyRelationshipId =
        copyRelationshipRepository.insert(
            masterProfileId,
            main.id(),
            callerUserId,
            slave.id(),
            moneyManagementProfileId,
            riskProfileId,
            BigDecimal.ZERO,
            "STRIPE_INVOICE");
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
