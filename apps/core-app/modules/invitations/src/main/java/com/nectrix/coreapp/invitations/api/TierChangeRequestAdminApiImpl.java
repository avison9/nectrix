package com.nectrix.coreapp.invitations.api;

import com.nectrix.coreapp.invitations.domain.TierChangeRequest;
import com.nectrix.coreapp.invitations.service.TierChangeAgreementNotAcceptedException;
import com.nectrix.coreapp.invitations.service.TierChangeRequestNotFoundException;
import com.nectrix.coreapp.invitations.service.TierChangeRequestNotPendingException;
import com.nectrix.coreapp.invitations.service.TierChangeRequestService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TierChangeRequestAdminApiImpl implements TierChangeRequestAdminApi {

  private final TierChangeRequestService service;

  public TierChangeRequestAdminApiImpl(TierChangeRequestService service) {
    this.service = service;
  }

  @Override
  public List<TierChangeRequestView> listByStatus(String status, int page, int pageSize) {
    return service.listByStatus(status, page, pageSize).stream()
        .map(TierChangeRequestAdminApiImpl::toView)
        .toList();
  }

  @Override
  public TierChangeRequestView getDetail(UUID id) {
    return toView(findOrThrow(id));
  }

  @Override
  public TierChangeRequestView approve(UUID id, UUID adminUserId, String reason) {
    try {
      return toView(service.approve(id, adminUserId, reason));
    } catch (TierChangeRequestNotFoundException e) {
      throw new NoSuchElementException("No such tier-change request: " + id);
    } catch (TierChangeRequestNotPendingException | TierChangeAgreementNotAcceptedException e) {
      throw new IllegalStateException(
          "Tier-change request " + id + " cannot be approved: " + e.getClass().getSimpleName());
    }
  }

  @Override
  public TierChangeRequestView reject(UUID id, UUID adminUserId, String reason) {
    try {
      return toView(service.reject(id, adminUserId, reason));
    } catch (TierChangeRequestNotFoundException e) {
      throw new NoSuchElementException("No such tier-change request: " + id);
    } catch (TierChangeRequestNotPendingException e) {
      throw new IllegalStateException("Tier-change request " + id + " is not pending");
    }
  }

  private TierChangeRequest findOrThrow(UUID id) {
    try {
      return service.getById(id);
    } catch (TierChangeRequestNotFoundException e) {
      throw new NoSuchElementException("No such tier-change request: " + id);
    }
  }

  private static TierChangeRequestView toView(TierChangeRequest r) {
    return new TierChangeRequestView(
        r.id(),
        r.userId(),
        r.targetRole(),
        r.status(),
        r.agreementVersion(),
        r.agreementAcceptedAt(),
        r.reviewedByUserId(),
        r.reviewReason(),
        r.reviewedAt(),
        r.createdAt());
  }
}
