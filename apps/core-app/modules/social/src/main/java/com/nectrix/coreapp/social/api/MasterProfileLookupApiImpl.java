package com.nectrix.coreapp.social.api;

import com.nectrix.coreapp.social.domain.MasterProfile;
import com.nectrix.coreapp.social.repository.MasterProfileRepository;
import com.nectrix.coreapp.social.service.MasterProfileService;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class MasterProfileLookupApiImpl implements MasterProfileLookupApi {

  private final MasterProfileRepository repository;
  private final MasterProfileService service;

  public MasterProfileLookupApiImpl(
      MasterProfileRepository repository, MasterProfileService service) {
    this.repository = repository;
    this.service = service;
  }

  @Override
  public MasterProfileSummaryView getMasterProfile(UUID masterProfileId) {
    return toView(findOrThrow(masterProfileId));
  }

  @Override
  public Optional<MasterProfileSummaryView> findByPrimaryBrokerAccountId(UUID brokerAccountId) {
    return repository.findByPrimaryBrokerAccountId(brokerAccountId).map(this::toView);
  }

  @Override
  public PrimaryBrokerAccountChange changePrimaryBrokerAccount(
      UUID masterProfileId, UUID actingUserId, UUID newBrokerAccountId) {
    MasterProfile existing = findOrThrow(masterProfileId);
    if (!existing.userId().equals(actingUserId)) {
      throw new AccessDeniedException(
          "Caller " + actingUserId + " does not own master profile " + masterProfileId);
    }
    UUID oldBrokerAccountId = existing.primaryBrokerAccountId();
    service.changePrimaryBrokerAccount(existing, newBrokerAccountId);
    return new PrimaryBrokerAccountChange(masterProfileId, oldBrokerAccountId, newBrokerAccountId);
  }

  private MasterProfile findOrThrow(UUID masterProfileId) {
    return repository
        .findById(masterProfileId)
        .orElseThrow(
            () -> new NoSuchElementException("No such master profile: " + masterProfileId));
  }

  private MasterProfileSummaryView toView(MasterProfile profile) {
    return new MasterProfileSummaryView(
        profile.id(),
        profile.userId(),
        profile.primaryBrokerAccountId(),
        profile.feeCollectionMethod(),
        profile.displayName(),
        profile.performanceFeePercent(),
        profile.minFollowerBalance());
  }
}
