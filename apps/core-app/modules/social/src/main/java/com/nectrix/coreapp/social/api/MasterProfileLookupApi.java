package com.nectrix.coreapp.social.api;

import java.util.UUID;

/**
 * TICKET-118 — cross-module-sanctioned surface letting {@code trading}'s invitation-acceptance
 * flow (the new {@code POST /copy-relationships/from-invitation} / {@code GET
 * /users/me/pending-invitation} endpoints) resolve a Master's {@code primary_broker_account_id}/
 * {@code fee_collection_method}/display name from a {@code master_profile_id} already known via
 * the accepted {@code Invitation} row, without importing {@code social.service}/{@code
 * social.repository} directly (enforced by ModuleBoundaryArchTest). Same one-way precedent {@code
 * IndividualProfileApi} already established for this exact module pair.
 */
public interface MasterProfileLookupApi {

  /**
   * @throws java.util.NoSuchElementException if no such master profile exists.
   */
  MasterProfileSummaryView getMasterProfile(UUID masterProfileId);
}
