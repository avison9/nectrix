package com.nectrix.coreapp.trading.api;

import java.util.UUID;

/**
 * #421 — cross-module-sanctioned surface letting {@code admin}'s {@code AdminController} create a
 * real {@code CopyRelationship} directly (SUPER_ADMIN/ADMIN manual follower-master link), without
 * importing {@code trading.service}/{@code trading.repository}/{@code trading.domain} directly
 * (enforced by ModuleBoundaryArchTest). Deliberately throws only standard JDK exception types —
 * same convention {@code AdminExceptionHandler} already documents for every other cross-module
 * surface it maps ({@code ImpersonationApi}, {@code BrokerAccountLookupApi}) — so no new exception
 * handler is needed in {@code admin}.
 */
public interface AdminCopyRelationshipApi {

  /**
   * @throws java.util.NoSuchElementException if {@code masterUserId} has no {@code master_profiles}
   *     row.
   * @throws IllegalArgumentException if {@code followerBrokerAccountId} isn't owned by {@code
   *     followerUserId}, or equals the master's own primary broker account.
   * @throws IllegalStateException if a non-terminal relationship already links this exact
   *     master/follower broker account pair.
   */
  LinkedCopyRelationshipView linkFollowerToMaster(
      UUID followerUserId, UUID masterUserId, UUID followerBrokerAccountId);

  record LinkedCopyRelationshipView(UUID id, String status, String masterDisplayName) {}
}
