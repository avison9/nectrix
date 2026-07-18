package com.nectrix.coreapp.trading.api;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.service.CopyRelationshipNotFoundException;
import com.nectrix.coreapp.trading.service.CopyRelationshipService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CopyRelationshipLookupApiImpl implements CopyRelationshipLookupApi {

  private final CopyRelationshipService service;

  public CopyRelationshipLookupApiImpl(CopyRelationshipService service) {
    this.service = service;
  }

  @Override
  public CopyRelationshipView getCopyRelationship(UUID id) {
    // Reuses CopyRelationshipService's own @PostAuthorize-guarded method (isOwnerOrStaff) — an
    // external call through the Spring proxy, not self-invocation, so the check actually fires.
    CopyRelationship relationship;
    try {
      relationship = service.getCopyRelationship(id);
    } catch (CopyRelationshipNotFoundException e) {
      // ..api.. surfaces only plain Java/domain types/exceptions (see
      // BrokerAccountLookupApiImpl's own identical Javadoc) — never a
      // module-internal type from ..service.. or ..web.. leaks out.
      throw new NoSuchElementException("No such copy relationship: " + id);
    }
    return new CopyRelationshipView(
        relationship.id(), relationship.followerUserId(), relationship.status());
  }
}
