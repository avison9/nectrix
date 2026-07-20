package com.nectrix.coreapp.invitations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TICKET-101 follow-up — cross-module-sanctioned surface for {@code bootstrap}'s {@code
 * BrokerAccountArchivalOrchestrator}, which needs to export and (once {@code trading}/{@code
 * billing}'s own referencing rows are already gone — see {@code
 * trading.api.CopyRelationshipArchivalApi}'s own ordering Javadoc) hard-delete a {@code
 * broker_accounts} row, without importing {@code invitations.service}/{@code
 * invitations.repository} directly. Same convention as {@code BrokerAccountLookupApi}.
 */
public interface BrokerAccountArchivalApi {

  /**
   * Read-only — must be called (and its result durably archived) before {@link #hardDelete}.
   * Deliberately excludes {@code credentials_ciphertext}/{@code credentials_key_version} — never
   * put even encrypted broker credentials in a long-lived audit blob (the domain type this is built
   * from doesn't carry those columns to begin with — see {@code invitations.domain.BrokerAccount}).
   *
   * @throws java.util.NoSuchElementException if no such broker account exists.
   */
  BrokerAccountExportView findForExport(UUID id);

  /**
   * The scheduled sweep's own candidate list — every account {@code DISCONNECTED} for at least
   * {@code olderThan} (e.g. {@code Duration.ofDays(90)}).
   */
  List<UUID> findStaleDisconnected(Duration olderThan);

  /**
   * Thin wrapper around the same {@code BrokerAccountService.deleteBrokerAccount} the self-service
   * Delete button calls — still enforces {@code DISCONNECTED}-required and still refuses to remove
   * a row some other table still references. Both cases surface as a plain {@link
   * IllegalStateException} (never this module's own internal exception types — see {@code
   * BrokerAccountLookupApiImpl}'s own Javadoc for why {@code ..api..} never leaks {@code
   * ..service..} types), since a correctly-ordered orchestrator (see {@code
   * trading.api.CopyRelationshipArchivalApi}'s own ordering Javadoc — every referencing row in
   * {@code trading}/{@code billing} must already be cleared before this is called) should never
   * actually hit either.
   *
   * @throws java.util.NoSuchElementException if no such broker account exists.
   */
  void hardDelete(UUID id);

  record BrokerAccountExportView(
      UUID id,
      UUID userId,
      String brokerType,
      String brokerAccountLogin,
      String displayLabel,
      boolean isDemo,
      String currency,
      String connectionRole,
      UUID openedViaIbLinkId,
      String connectionStatus,
      Instant lastHealthCheckAt,
      String brokerName,
      String serverName) {}
}
