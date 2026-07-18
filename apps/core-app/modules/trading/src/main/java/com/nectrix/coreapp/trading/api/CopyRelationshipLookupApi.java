package com.nectrix.coreapp.trading.api;

import java.util.UUID;

/**
 * Cross-module-sanctioned surface for reading a {@code CopyRelationship}'s ownership-relevant
 * fields — lets {@code bootstrap}'s WS handler (TICKET-116's {@code copy-relationships} channel)
 * reuse the same ownership check {@code CopyRelationshipController} uses, without importing {@code
 * trading.service}/{@code trading.repository}/{@code trading.domain} directly. Same convention as
 * {@code invitations.api.BrokerAccountLookupApi}.
 *
 * @throws java.util.NoSuchElementException if no such copy relationship exists.
 */
public interface CopyRelationshipLookupApi {

  CopyRelationshipView getCopyRelationship(UUID id);
}
