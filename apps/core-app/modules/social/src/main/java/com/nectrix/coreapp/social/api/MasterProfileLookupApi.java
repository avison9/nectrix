package com.nectrix.coreapp.social.api;

import java.util.Optional;
import java.util.UUID;

/**
 * TICKET-118 — cross-module-sanctioned surface letting {@code trading}'s invitation-acceptance flow
 * (the new {@code POST /copy-relationships/from-invitation} / {@code GET
 * /users/me/pending-invitation} endpoints) resolve a Master's {@code primary_broker_account_id}/
 * {@code fee_collection_method}/display name from a {@code master_profile_id} already known via the
 * accepted {@code Invitation} row, without importing {@code social.service}/{@code
 * social.repository} directly (enforced by ModuleBoundaryArchTest). Same one-way precedent {@code
 * IndividualProfileApi} already established for this exact module pair.
 */
public interface MasterProfileLookupApi {

  /**
   * @throws java.util.NoSuchElementException if no such master profile exists.
   */
  MasterProfileSummaryView getMasterProfile(UUID masterProfileId);

  /**
   * Bugfix — lets {@code bootstrap}'s archival flow ask "is this broker account currently a
   * Master's primary?" before hard-deleting it, without importing {@code social.repository}
   * directly.
   */
  Optional<MasterProfileSummaryView> findByPrimaryBrokerAccountId(UUID brokerAccountId);

  /**
   * #421 — lets {@code trading}'s {@code AdminCopyLinkService} resolve a would-be Master's own
   * {@code master_profiles} row from just their {@code user_id} (an admin identifies the Master by
   * email, resolved to a {@code userId} first), without importing {@code social.repository}
   * directly. Empty means this user isn't (yet) a Master.
   */
  Optional<MasterProfileSummaryView> findByUserId(UUID userId);

  /**
   * Bugfix — lets a Master change their primary broker account, with the new account's ownership
   * validated same as {@code MasterProfileService#create} already does. Deliberately takes {@code
   * actingUserId} as a plain param rather than relying on {@code @PostAuthorize}/the request's own
   * {@code SecurityContext} — this is also called from {@code bootstrap}'s scheduled archival
   * sweep, which has no HTTP request/JWT/authenticated principal at all (same reasoning {@code
   * BrokerAccountArchivalApiImpl}'s own Javadoc gives for bypassing {@code
   * BrokerAccountService#getBrokerAccount}'s {@code @PostAuthorize}). The on-demand caller (a real
   * per-request principal) still gets a real ownership check — just done manually here instead.
   *
   * @throws java.util.NoSuchElementException if no such master profile exists.
   * @throws org.springframework.security.access.AccessDeniedException if {@code actingUserId}
   *     doesn't own {@code masterProfileId}.
   */
  PrimaryBrokerAccountChange changePrimaryBrokerAccount(
      UUID masterProfileId, UUID actingUserId, UUID newBrokerAccountId);
}
