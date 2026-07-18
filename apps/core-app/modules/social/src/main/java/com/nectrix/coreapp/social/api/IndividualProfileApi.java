package com.nectrix.coreapp.social.api;

import java.util.UUID;

/**
 * TICKET-114 — cross-module-sanctioned surface letting {@code trading}'s self-service Individual
 * copy-setup endpoint obtain the private {@code master_profiles} row {@code copy_relationships}'
 * {@code NOT NULL} FK requires, without importing {@code social.service}/{@code social.repository}
 * directly (enforced by ModuleBoundaryArchTest) — same precedent {@code
 * invitations.api.BrokerAccountLookupApi} established for {@code social}'s own dependency on {@code
 * invitations}, just the other direction.
 */
public interface IndividualProfileApi {

  /** Idempotent — returns the existing row on a repeat call for the same {@code userId}. */
  UUID findOrCreatePrivateProfile(UUID userId, UUID mainBrokerAccountId);
}
