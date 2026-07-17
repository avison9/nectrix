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
   * 409 if this user already has a profile ({@code user_id} is {@code UNIQUE} — enforced here first
   * rather than relying on the DB constraint alone, so the caller gets a clean 409 instead of a raw
   * {@code DataIntegrityViolationException}). 403 if {@code primaryBrokerAccountId} isn't a real
   * broker account owned by this same caller — a Master cannot nominate someone else's account as
   * their own primary trading account.
   */
  public MasterProfile create(
      UUID userId,
      UUID primaryBrokerAccountId,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod) {
    var existing = repository.findByUserId(userId);
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
            FeeCollectionMethods.resolveOrDefault(feeCollectionMethod));
    return repository.findById(id).orElseThrow(MasterProfileNotFoundException::new);
  }

  /**
   * TICKET-114 — the Individual-mode counterpart to {@link #create}: a system-created, private
   * ({@code is_public=false}) profile backing a self-service "main account" copy setup, never
   * user-role-gated (unlike {@link #create}'s {@code @PreAuthorize("hasRole('MASTER')")} on the
   * controller) since nothing here is user-initiated in the marketplace sense. Idempotent — a
   * second call for the same {@code userId} returns the existing row rather than a 409, since
   * {@code IndividualCopySetupService} may call this again for a later slave-account addition.
   */
  public MasterProfile findOrCreatePrivateProfile(UUID userId, UUID mainBrokerAccountId) {
    return repository
        .findByUserId(userId)
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
                      false);
              return repository.findById(id).orElseThrow(MasterProfileNotFoundException::new);
            });
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
      Boolean isPublic) {
    repository.update(
        existing.id(),
        displayName != null ? displayName : existing.displayName(),
        bio != null ? bio : existing.bio(),
        strategyTags != null ? strategyTags : existing.strategyTags(),
        performanceFeePercent != null ? performanceFeePercent : existing.performanceFeePercent(),
        isPublic != null ? isPublic : existing.isPublic());
    return repository.findById(existing.id()).orElseThrow(MasterProfileNotFoundException::new);
  }
}
