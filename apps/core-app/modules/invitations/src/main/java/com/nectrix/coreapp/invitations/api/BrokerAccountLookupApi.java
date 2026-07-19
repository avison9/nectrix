package com.nectrix.coreapp.invitations.api;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module-sanctioned surface for reading a {@code BrokerAccount} — lets {@code admin} module's
 * staff-view endpoint reuse the exact same lookup {@code BrokerAccountController} uses, without
 * importing {@code invitations.service}/{@code invitations.repository} directly. Returns {@link
 * BrokerAccountView}, not {@code invitations.domain.BrokerAccount} — see that type's Javadoc for
 * why.
 *
 * @throws java.util.NoSuchElementException if no such broker account exists.
 */
public interface BrokerAccountLookupApi {

  BrokerAccountView getBrokerAccount(UUID id);

  /**
   * TICKET-117 — admin user-detail view's linked-broker-accounts list. Unlike {@link
   * #getBrokerAccount}, this has no ownership check to defer to (there's no single account's
   * {@code @PostAuthorize} to reuse) — callers are expected to already be route-gated to
   * ADMIN/SUPPORT before reaching this.
   */
  List<BrokerAccountView> listForUser(UUID userId);
}
