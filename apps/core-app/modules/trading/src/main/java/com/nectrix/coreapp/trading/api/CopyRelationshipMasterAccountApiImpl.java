package com.nectrix.coreapp.trading.api;

import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CopyRelationshipMasterAccountApiImpl implements CopyRelationshipMasterAccountApi {

  private final CopyRelationshipRepository repository;

  public CopyRelationshipMasterAccountApiImpl(CopyRelationshipRepository repository) {
    this.repository = repository;
  }

  @Override
  public void reassignMasterBrokerAccount(
      UUID masterProfileId, UUID oldBrokerAccountId, UUID newBrokerAccountId) {
    repository.reassignMasterBrokerAccount(masterProfileId, oldBrokerAccountId, newBrokerAccountId);
  }
}
