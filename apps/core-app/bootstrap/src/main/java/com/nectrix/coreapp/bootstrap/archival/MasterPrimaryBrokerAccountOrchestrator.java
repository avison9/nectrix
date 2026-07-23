package com.nectrix.coreapp.bootstrap.archival;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.social.api.PrimaryBrokerAccountChange;
import com.nectrix.coreapp.trading.api.CopyRelationshipMasterAccountApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bugfix — the one place that can reach {@code social} AND {@code trading}'s own {@code ..api..}
 * facades together (same module-dependency-cycle reasoning {@code
 * BrokerAccountArchivalOrchestrator}'s own Javadoc gives: {@code social} depends on {@code
 * invitations}, not {@code trading}, so nothing inside {@code social} can cascade a primary-account
 * change into {@code copy_relationships} itself).
 *
 * <p>Reused by two callers: {@code MasterPrimaryBrokerAccountController} (a real Master
 * self-service request) and {@link BrokerAccountArchivalOrchestrator} (auto-reassignment when the
 * account being archived is currently someone's primary) — one code path, so neither can silently
 * drift from the other's cascade behavior.
 */
@Service
public class MasterPrimaryBrokerAccountOrchestrator {

  private final MasterProfileLookupApi masterProfileLookupApi;
  private final CopyRelationshipMasterAccountApi copyRelationshipMasterAccountApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;

  public MasterPrimaryBrokerAccountOrchestrator(
      MasterProfileLookupApi masterProfileLookupApi,
      CopyRelationshipMasterAccountApi copyRelationshipMasterAccountApi,
      BrokerAccountLookupApi brokerAccountLookupApi) {
    this.masterProfileLookupApi = masterProfileLookupApi;
    this.copyRelationshipMasterAccountApi = copyRelationshipMasterAccountApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  /**
   * Updates {@code master_profiles.primary_broker_account_id} (ownership-checked against {@code
   * actingUserId}, see {@code MasterProfileLookupApi#changePrimaryBrokerAccount}'s own Javadoc for
   * why that check lives there and not in a {@code @PostAuthorize} annotation), then cascades into
   * any of this master's existing non-terminal {@code copy_relationships} rows still pointing at
   * the old account.
   */
  public PrimaryBrokerAccountChange changePrimaryBrokerAccount(
      UUID masterProfileId, UUID actingUserId, UUID newBrokerAccountId) {
    PrimaryBrokerAccountChange change =
        masterProfileLookupApi.changePrimaryBrokerAccount(
            masterProfileId, actingUserId, newBrokerAccountId);
    copyRelationshipMasterAccountApi.reassignMasterBrokerAccount(
        change.masterProfileId(), change.oldBrokerAccountId(), change.newBrokerAccountId());
    return change;
  }

  /**
   * Bugfix — {@link BrokerAccountArchivalOrchestrator#archiveAndDelete}'s own pre-{@code
   * hardDelete} check: if {@code brokerAccountId} is currently a Master's primary, find another of
   * that same user's own broker accounts eligible to take over (a real, {@code CONNECTED} account
   * with a Master-capable {@code connectionRole}, distinct from the one being deleted) and reassign
   * to it. {@link Optional#empty()} means either this account isn't anyone's primary at all
   * (nothing to do), or it is but no eligible replacement exists — the caller distinguishes these
   * by checking {@code masterProfileLookupApi.findByPrimaryBrokerAccountId} itself first; this
   * method is only ever called once that's already confirmed non-empty, so an empty result here
   * always means "no eligible replacement," never "not anyone's primary."
   */
  public Optional<PrimaryBrokerAccountChange> autoReassignForArchival(
      MasterProfileSummaryView profile, UUID brokerAccountId) {
    List<BrokerAccountView> candidates = brokerAccountLookupApi.listForUser(profile.userId());
    Optional<BrokerAccountView> replacement =
        candidates.stream()
            .filter(a -> !a.id().equals(brokerAccountId))
            .filter(
                a -> "MASTER_ONLY".equals(a.connectionRole()) || "BOTH".equals(a.connectionRole()))
            .filter(a -> "CONNECTED".equals(a.connectionStatus()))
            .findFirst();
    if (replacement.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        changePrimaryBrokerAccount(profile.id(), profile.userId(), replacement.get().id()));
  }
}
