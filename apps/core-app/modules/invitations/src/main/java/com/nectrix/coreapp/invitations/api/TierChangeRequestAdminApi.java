package com.nectrix.coreapp.invitations.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TICKET-122 — the cross-module-sanctioned surface for {@code modules:admin}'s {@code
 * AdminController} to list/approve/reject tier-change requests without importing {@code
 * invitations.service}/{@code invitations.repository} directly. Same convention {@code
 * BrokerAccountLookupApi} already establishes for that module's other admin-facing lookups.
 */
public interface TierChangeRequestAdminApi {

  /** The pending-review queue (or any other status), oldest-first. */
  List<TierChangeRequestView> listByStatus(String status, int page, int pageSize);

  /**
   * @throws java.util.NoSuchElementException if no such request exists — {@code
   *     com.nectrix.coreapp.invitations.service.TierChangeRequestNotFoundException} never crosses
   *     this boundary (see {@code BrokerAccountArchivalApiImpl}'s own Javadoc for why {@code
   *     ..api..} never leaks {@code ..service..} exception types).
   */
  TierChangeRequestView getDetail(UUID id);

  /**
   * Grants the requested role (via {@code auth.api.UserProvisioningApi}) and dispatches an
   * approved-outcome notification to the requester.
   *
   * @throws IllegalStateException if the request isn't currently {@code PENDING}, or its agreement
   *     wasn't accepted (AC5 — checked again here, not just trusted from submission time).
   */
  TierChangeRequestView approve(UUID id, UUID adminUserId, String reason);

  /**
   * Leaves the requester's roles unchanged and dispatches a rejected-outcome notification (with
   * {@code reason}, if given).
   *
   * @throws IllegalStateException if the request isn't currently {@code PENDING}.
   */
  TierChangeRequestView reject(UUID id, UUID adminUserId, String reason);

  record TierChangeRequestView(
      UUID id,
      UUID userId,
      String targetRole,
      String status,
      String agreementVersion,
      Instant agreementAcceptedAt,
      UUID reviewedByUserId,
      String reviewReason,
      Instant reviewedAt,
      Instant createdAt) {}
}
