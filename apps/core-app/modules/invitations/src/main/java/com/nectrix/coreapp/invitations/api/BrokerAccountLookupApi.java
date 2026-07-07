package com.nectrix.coreapp.invitations.api;

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
}
