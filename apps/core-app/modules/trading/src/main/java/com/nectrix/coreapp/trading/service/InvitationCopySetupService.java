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
    MasterProfileSummaryView master =
        masterProfileLookupApi.getMasterProfile(invitation.masterProfileId());
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
    MasterProfileSummaryView master =
        masterProfileLookupApi.getMasterProfile(invitation.masterProfileId());

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
        "BROKER_PARTNERSHIP".equals(master.feeCollectionMethod())
            ? "PENDING_AGREEMENT"
            : "PENDING_RISK_ACK";
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

  /**
   * Always inserts a fresh row (never reuses an existing profile id) — see this class's Javadoc.
   * {@code suggestedId} is null for an invitation with no suggested profile, in which case every
   * field falls through to a plain hardcoded default.
   */
  private UUID insertMoneyManagementProfile(
      UUID suggestedId,
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode) {
    MoneyManagementProfile suggested =
        suggestedId == null
            ? null
            : moneyManagementProfileRepository.findById(suggestedId).orElse(null);
    String sMethod = suggested == null ? null : suggested.method();
    BigDecimal sFixedLotSize = suggested == null ? null : suggested.fixedLotSize();
    BigDecimal sMultiplier = suggested == null ? null : suggested.multiplier();
    BigDecimal sRiskPercent = suggested == null ? null : suggested.riskPercent();
    String sRoundingMode = suggested == null ? null : suggested.roundingMode();
    String sCustomFormulaExpr = suggested == null ? null : suggested.customFormulaExpr();
    return moneyManagementProfileRepository.insert(
        method != null ? method : (sMethod != null ? sMethod : "MULTIPLIER"),
        fixedLotSize != null ? fixedLotSize : sFixedLotSize,
        multiplier != null ? multiplier : (sMultiplier != null ? sMultiplier : BigDecimal.ONE),
        riskPercent != null ? riskPercent : sRiskPercent,
        sCustomFormulaExpr,
        roundingMode != null ? roundingMode : sRoundingMode);
  }

  private UUID insertRiskProfile(
      UUID suggestedId,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips) {
    RiskProfile suggested =
        suggestedId == null ? null : riskProfileRepository.findById(suggestedId).orElse(null);
    BigDecimal sMaxLotPerTrade = suggested == null ? null : suggested.maxLotPerTrade();
    Integer sMaxOpenPositions = suggested == null ? null : suggested.maxOpenPositions();
    BigDecimal sMaxExposurePerSymbolLots =
        suggested == null ? null : suggested.maxExposurePerSymbolLots();
    BigDecimal sMaxTotalExposureLots = suggested == null ? null : suggested.maxTotalExposureLots();
    BigDecimal sMaxSlippagePips = suggested == null ? null : suggested.maxSlippagePips();
    return riskProfileRepository.insert(
        maxLotPerTrade != null ? maxLotPerTrade : sMaxLotPerTrade,
        maxOpenPositions != null ? maxOpenPositions : sMaxOpenPositions,
        sMaxExposurePerSymbolLots,
        sMaxTotalExposureLots,
        maxSlippagePips != null ? maxSlippagePips : sMaxSlippagePips);
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
