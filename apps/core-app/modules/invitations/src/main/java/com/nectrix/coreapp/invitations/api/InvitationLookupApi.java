package com.nectrix.coreapp.invitations.api;

import java.util.UUID;

/**
 * TICKET-118 — cross-module-sanctioned surface letting {@code trading}'s invitation-acceptance flow
 * ({@code GET /users/me/pending-invitation}, {@code POST /copy-relationships/from-invitation}) read
 * an accepted invitation's suggested defaults, without importing {@code invitations.service}/{@code
 * invitations.repository}/{@code invitations.domain} directly (enforced by ModuleBoundaryArchTest).
 * Same one-way precedent {@code BrokerAccountLookupApi} already established for this exact module
 * pair.
 *
 * @throws java.util.NoSuchElementException if no such invitation exists.
 */
public interface InvitationLookupApi {

  InvitationView getInvitation(UUID id);
}
