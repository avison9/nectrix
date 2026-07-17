package com.nectrix.coreapp.social.api;

import com.nectrix.coreapp.social.service.MasterProfileService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IndividualProfileApiImpl implements IndividualProfileApi {

  private final MasterProfileService service;

  public IndividualProfileApiImpl(MasterProfileService service) {
    this.service = service;
  }

  @Override
  public UUID findOrCreatePrivateProfile(UUID userId, UUID mainBrokerAccountId) {
    return service.findOrCreatePrivateProfile(userId, mainBrokerAccountId).id();
  }
}
