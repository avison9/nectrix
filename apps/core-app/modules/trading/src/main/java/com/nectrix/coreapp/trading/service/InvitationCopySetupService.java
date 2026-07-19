package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import com.nectrix.coreapp.invitations.api.InvitationLookupApi;
import com.nectrix.coreapp.invitations.api.InvitationView;
import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.domain.MoneyManagementProfile;
import com.nectrix.coreapp.trading.domain.RiskProfile;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.MoneyManagementProfileRepository;
import com.nectrix.coreapp.trading.repository.RiskProfileRepository;
import com.nectrix.coreapp.trading.repository.UserInvitationLookupRepository;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-118 — the step after {@code POST /auth/accept-invite}: once the (new-or-existing)
 * Follower has linked a broker account (TICKET-110) and reviewed the Master's suggested
 * money-management/risk defaults, this creates the real {@code CopyRelationship} row,
 * {@code originating_invitation_id} set.
 *
 * <p>Deliberately does NOT reuse {@link IndividualCopySetupService}: that service is Individual-
 * mode-only (rejects real Master/Follower callers outright) and hardcodes MM/risk defaults
 * server-side rather than letting the client review/adjust them — a materially different contract
 * from what this ticket's own AC wants.
 *
 * <p>{@link #getPendingInvitation} only ever resolves the ONE invitation that created the caller's
 * very first account ({@code users.created_via_invitation_id}) — a pre-existing user who accepts a
 * SECOND Master's invite (this ticket's own explicit multi-master AC) has no such column update,
 * so the frontend instead carries that invitation's id forward itself, from the moment {@code GET
 * /invitations/by-token/{token}} first returned it, through {@code accept-invite}, into {@code
 * POST /copy-relationships/from-invitation}'s own explicit {@code invitationId} field — this
 * endpoint is the reliable path for both cases; {@link #getPendingInvitation} is a convenience
 * default for a caller who navigates to onboarding without that context (e.g. closed the tab).
 */
@Service
public class InvitationCopySetupService {

  private final InvitationLookupApi invitationLookupApi;
  private final MasterProfileLookupApi masterProfileLookupApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final UserInvitationLookupRepository userInvitationLookupRepository;
  private final MoneyManagementProfileRepository moneyManagementProfileRepository;
  private final RiskProfileRepository riskProfileRepository;
  private final CopyRelationshipRepository copyRelationshipRepository;

  public InvitationCopySetupService(
      InvitationLookupApi invitationLookupApi,
      MasterProfileLookupApi masterProfileLookupApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      UserInvitationLookupRepository userInvitationLookupRepository,
      MoneyManagementProfileRepository moneyManagementProfileRepository,
      RiskProfileRepository riskProfileRepository,
      CopyRelationshipRepository copyRelationshipRepository) {
    this.invitationLookupApi = invitationLookupApi;
    this.masterProfileLookupApi = masterProfileLookupApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.userInvitationLookupRepository = userInvitationLookupRepository;
    this.moneyManagementProfileRepository = moneyManagementProfileRepository;
    this.riskProfileRepository = riskProfileRepository;
    this.copyRelationshipRepository = copyRelationshipRepository;
  }

  public Optional<PendingInvitation> getPendingInvitation(UUID callerUserId) {
    Optional<UUID> invitationId =
        userInvitationLookupRepository.findCreatedViaInvitationId(callerUserId);
    if (invitationId.isEmpty()) {
      return Optional.empty();
    }
    if (copyRelationshipRepository.existsByOriginatingInvitationId(invitationId.get())) {
      return Optional.empty(); // already actioned
    }
    InvitationView invitation = lookupInvitation(invitationId.get());
    MasterProfileSummaryView master = masterProfileLookupApi.getMasterProfile(invitation.masterProfileId());
    return Optional.of(
        new PendingInvitation(
            invitation.id(),
            master.displayName(),
            invitation.suggestedMoneyManagementProfileId(),
            invitation.suggestedRiskProfileId()));
  }

  public CopyRelationship createFromInvitation(
      UUID callerUserId,
      UUID invitationId,
      UUID followerBrokerAccountId,
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips) {
    InvitationView invitation = lookupInvitation(invitationId);
    if (!"ACCEPTED".equals(invitation.status())
        || !callerUserId.equals(invitation.acceptedByUserId())) {
      throw new InvitationNotAcceptedException();
    }
    if (copyRelationshipRepository.existsByOriginatingInvitationId(invitationId)) {
      throw new InvitationAlreadyUsedException();
    }
    BrokerAccountView followerAccount = lookupOwned(callerUserId, followerBrokerAccountId);
    MasterProfileSummaryView master = masterProfileLookupApi.getMasterProfile(invitation.masterProfileId());

    UUID moneyManagementProfileId =
        insertMoneyManagementProfile(
            invitation.suggestedMoneyManagementProfileId(),
            method,
            fixedLotSize,
            multiplier,
            riskPercent,
            roundingMode);
    UUID riskProfileId =
        insertRiskProfile(
            invitation.suggestedRiskProfileId(),
            maxLotPerTrade,
            maxOpenPositions,
            maxSlippagePips);

    String status =
        "BROKER_PARTNERSHIP".equals(master.feeCollectionMethod()) ? "PENDING_AGREEMENT" : "PENDING_RISK_ACK";
    UUID copyRelationshipId =
        copyRelationshipRepository.insertFromInvitation(
            master.id(),
            master.primaryBrokerAccountId(),
            callerUserId,
            followerAccount.id(),
            moneyManagementProfileId,
            riskProfileId,
            master.performanceFeePercent(),
            master.feeCollectionMethod(),
            status,
            invitationId);
    return copyRelationshipRepository
        .findById(copyRelationshipId)
        .orElseThrow(CopyRelationshipNotFoundException::new);
  }

  /** Always inserts a fresh row (never reuses an existing profile id) — see this class's Javadoc. */
  private UUID insertMoneyManagementProfile(
      UUID suggestedId,
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode) {
    MoneyManagementProfile suggested =
        suggestedId != null ? moneyManagementProfileRepository.findById(suggestedId).orElse(null) : null;
    return moneyManagementProfileRepository.insert(
        method != null ? method : (suggested != null ? suggested.method() : "MULTIPLIER"),
        fixedLotSize != null ? fixedLotSize : (suggested != null ? suggested.fixedLotSize() : null),
        multiplier != null ? multiplier : (suggested != null ? suggested.multiplier() : BigDecimal.ONE),
        riskPercent != null ? riskPercent : (suggested != null ? suggested.riskPercent() : null),
        suggested != null ? suggested.customFormulaExpr() : null,
        roundingMode != null ? roundingMode : (suggested != null ? suggested.roundingMode() : null));
  }

  private UUID insertRiskProfile(
      UUID suggestedId,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips) {
    RiskProfile suggested =
        suggestedId != null ? riskProfileRepository.findById(suggestedId).orElse(null) : null;
    return riskProfileRepository.insert(
        maxLotPerTrade != null ? maxLotPerTrade : (suggested != null ? suggested.maxLotPerTrade() : null),
        maxOpenPositions != null
            ? maxOpenPositions
            : (suggested != null ? suggested.maxOpenPositions() : null),
        suggested != null ? suggested.maxExposurePerSymbolLots() : null,
        suggested != null ? suggested.maxTotalExposureLots() : null,
        maxSlippagePips != null ? maxSlippagePips : (suggested != null ? suggested.maxSlippagePips() : null));
  }

  private InvitationView lookupInvitation(UUID invitationId) {
    try {
      return invitationLookupApi.getInvitation(invitationId);
    } catch (NoSuchElementException e) {
      throw new InvitationNotFoundException();
    }
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

  public record PendingInvitation(
      UUID invitationId,
      String masterDisplayName,
      UUID suggestedMoneyManagementProfileId,
      UUID suggestedRiskProfileId) {}
}
