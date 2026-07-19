package com.nectrix.coreapp.social.api;

import com.nectrix.coreapp.social.domain.MasterProfile;
import com.nectrix.coreapp.social.repository.MasterProfileRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MasterProfileLookupApiImpl implements MasterProfileLookupApi {

  private final MasterProfileRepository repository;

  public MasterProfileLookupApiImpl(MasterProfileRepository repository) {
    this.repository = repository;
  }

  @Override
  public MasterProfileSummaryView getMasterProfile(UUID masterProfileId) {
    MasterProfile profile =
        repository
            .findById(masterProfileId)
            .orElseThrow(
                () -> new NoSuchElementException("No such master profile: " + masterProfileId));
    return new MasterProfileSummaryView(
        profile.id(),
        profile.primaryBrokerAccountId(),
        profile.feeCollectionMethod(),
        profile.displayName(),
        profile.performanceFeePercent());
  }
}
