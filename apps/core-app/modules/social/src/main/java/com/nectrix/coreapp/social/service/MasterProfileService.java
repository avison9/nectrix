package com.nectrix.coreapp.social.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import com.nectrix.coreapp.social.domain.MasterProfile;
import com.nectrix.coreapp.social.repository.MasterProfileRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

/**
 * Self-service Master profile creation — the {@code MASTER} role itself is Admin-provisioned
 * (nectrix_plan/docs/07-auth-onboarding-broker-linking.md §7.0), this is the Master filling in
 * their own profile details on first login. {@code @PreAuthorize("hasRole('MASTER')")} lives on the
 * controller (see {@code MasterProfileController}), same convention {@code AdminController} already
 * established for role-gated (as opposed to ownership-gated) endpoints.
 */
@Service
public class MasterProfileService {

  private final MasterProfileRepository repository;
  private final BrokerAccountLookupApi brokerAccountLookupApi;

  public MasterProfileService(
      MasterProfileRepository repository, BrokerAccountLookupApi brokerAccountLookupApi) {
    this.repository = repository;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  /**
   * TICKET-125 — 409 only if THIS SPECIFIC broker account already backs a profile ({@code
   * primary_broker_account_id} is {@code UNIQUE} — enforced here first rather than relying on the
   * DB constraint alone, so the caller gets a clean 409 instead of a raw {@code
   * DataIntegrityViolationException}). A user with multiple eligible broker accounts can call this
   * once per account, one profile per strategy — no longer limited to a single profile per user.
   * 403 if {@code primaryBrokerAccountId} isn't a real broker account owned by this same caller — a
   * Master cannot nominate someone else's account as their own primary trading account.
   */
  public MasterProfile create(
      UUID userId,
      UUID primaryBrokerAccountId,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod,
      BigDecimal minFollowerBalance) {
    var existing = repository.findByPrimaryBrokerAccountId(primaryBrokerAccountId);
    if (existing.isPresent()) {
      throw new MasterProfileAlreadyExistsException(existing.get().id());
    }
    BrokerAccountView account = lookupOwnedBrokerAccount(userId, primaryBrokerAccountId);
    UUID id =
        repository.insert(
            userId,
            account.id(),
            displayName,
            bio,
            strategyTags,
            performanceFeePercent,
            FeeCollectionMethods.resolveOrDefault(feeCollectionMethod),
            minFollowerBalance != null ? normalizeMinFollowerBalance(minFollowerBalance) : null);
    return repository.findById(id).orElseThrow(MasterProfileNotFoundException::new);
  }

  /**
   * TICKET-114 — the Individual-mode counterpart to {@link #create}: a system-created, private
   * ({@code is_public=false}) profile backing a self-service "main account" copy setup, never
   * user-role-gated (unlike {@link #create}'s {@code @PreAuthorize("hasRole('MASTER')")} on the
   * controller) since nothing here is user-initiated in the marketplace sense. Idempotent — a
   * second call for the same {@code (userId, mainBrokerAccountId)} pair returns the existing row
   * rather than a 409, since {@code IndividualCopySetupService} may call this again for a later
   * slave-account addition. TICKET-125 — looked up by {@code mainBrokerAccountId} (now the real
   * per-profile identity key), not bare {@code userId}: this same user may separately have real,
   * public Master profiles for other broker accounts, which must never be mistaken for this private
   * one.
   */
  public MasterProfile findOrCreatePrivateProfile(UUID userId, UUID mainBrokerAccountId) {
    return repository
        .findByPrimaryBrokerAccountId(mainBrokerAccountId)
        .orElseGet(
            () -> {
              UUID id =
                  repository.insert(
                      userId,
                      mainBrokerAccountId,
                      "Individual",
                      null,
                      List.of(),
                      BigDecimal.ZERO,
                      "STRIPE_INVOICE",
                      false,
                      null);
              return repository.findById(id).orElseThrow(MasterProfileNotFoundException::new);
            });
  }

  /**
   * Bugfix — {@code existing} must already have passed {@link #getMasterProfile}'s ownership check,
   * same fetch-then-check-then-mutate discipline {@link #updateSettings} established. Reuses {@link
   * #lookupOwnedBrokerAccount} — same rule {@link #create} already enforces: a Master cannot
   * nominate someone else's broker account as their own primary trading account. Cascading this
   * change to any existing {@code copy_relationships} rows still pointing at the OLD primary
   * account is the caller's responsibility (this module can't reach {@code trading} — see {@code
   * bootstrap.archival.MasterPrimaryBrokerAccountOrchestrator}, the one place that can).
   */
  public MasterProfile changePrimaryBrokerAccount(MasterProfile existing, UUID newBrokerAccountId) {
    lookupOwnedBrokerAccount(existing.userId(), newBrokerAccountId);
    repository.updatePrimaryBrokerAccount(existing.id(), newBrokerAccountId);
    return repository.findById(existing.id()).orElseThrow(MasterProfileNotFoundException::new);
  }

  private BrokerAccountView lookupOwnedBrokerAccount(UUID userId, UUID brokerAccountId) {
    BrokerAccountView account;
    try {
      account = brokerAccountLookupApi.getBrokerAccount(brokerAccountId);
    } catch (NoSuchElementException e) {
      throw new BrokerAccountNotOwnedException();
    }
    if (!account.userId().equals(userId)) {
      throw new BrokerAccountNotOwnedException();
    }
    return account;
  }

  @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")
  public MasterProfile getMasterProfile(UUID id) {
    return repository.findById(id).orElseThrow(MasterProfileNotFoundException::new);
  }

  /**
   * TICKET-116 — the "do I already have one" lookup {@link #create}'s own Javadoc originally
   * flagged as missing (previously only discoverable via a 409 on create). Needed so a Master-role
   * caller can reach their own profile id(s) without knowing them up front (e.g. the Analytics
   * page). Inherently self-scoped (queries by the caller's own {@code userId}, never a
   * client-supplied one) — no {@code @PostAuthorize} needed, unlike {@link #getMasterProfile}.
   *
   * <p>TICKET-125 — now returns every profile this user owns (possibly empty, never a 404 — "you
   * have zero profiles" is a legitimate, valid state for a MASTER-role user who hasn't created one
   * yet, distinct from the old single-profile version's "not found" semantics).
   */
  public List<MasterProfile> getMyProfiles(UUID userId) {
    return repository.findAllByUserId(userId);
  }

  /**
   * {@code existing} must already have passed {@link #getMasterProfile}'s ownership check — same
   * fetch-then-check-then-mutate discipline {@code BrokerAccountService} established
   * (self-invocation bypasses Spring AOP's {@code @PostAuthorize} proxy, so it can't live inside
   * this method itself).
   */
  public MasterProfile updateSettings(
      MasterProfile existing,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      Boolean isPublic,
      BigDecimal minFollowerBalance) {
    repository.update(
        existing.id(),
        displayName != null ? displayName : existing.displayName(),
        bio != null ? bio : existing.bio(),
        strategyTags != null ? strategyTags : existing.strategyTags(),
        performanceFeePercent != null ? performanceFeePercent : existing.performanceFeePercent(),
        isPublic != null ? isPublic : existing.isPublic(),
        minFollowerBalance != null
            ? normalizeMinFollowerBalance(minFollowerBalance)
            : existing.minFollowerBalance());
    return repository.findById(existing.id()).orElseThrow(MasterProfileNotFoundException::new);
  }

  /**
   * Feature — {@code 0} is treated as "no minimum" (stored as {@code NULL}) rather than a real
   * floor, since a real balance is never negative anyway — this gives the edit form an explicit way
   * to CLEAR a previously-set minimum without needing a separate null-vs-absent-field convention
   * this codebase doesn't otherwise have (every other {@link #updateSettings} field already means
   * "null in the request = keep existing," which would otherwise make a minimum, once set,
   * permanent).
   */
  private static BigDecimal normalizeMinFollowerBalance(BigDecimal value) {
    return value.signum() == 0 ? null : value;
  }
}
