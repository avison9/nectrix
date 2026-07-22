package com.nectrix.coreapp.trading.api;

import java.util.UUID;

/**
 * TICKET-101 follow-up — cross-module-sanctioned surface for {@code bootstrap}'s {@code
 * BrokerAccountArchivalOrchestrator}, which needs to export-then-delete this module's own {@code
 * copy_relationships}/{@code copied_trades}/{@code trade_signals} rows for a broker account being
 * permanently deleted, without importing {@code trading.service}/{@code trading.repository}/{@code
 * trading.domain} directly. Same convention as {@code CopyRelationshipLookupApi}.
 */
public interface CopyRelationshipArchivalApi {

  /**
   * Read-only — must be called (and its result durably archived) before {@link
   * #deleteForBrokerAccount}.
   */
  CopyRelationshipArchiveExport findForExport(UUID brokerAccountId);

  /**
   * Deletes, in the only safe order (children before parents, since neither {@code copied_trades}
   * nor {@code trade_signals} has an {@code ON DELETE CASCADE} of its own): {@code copied_trades}
   * for every relationship this account is party to, then {@code trade_signals} this account
   * broadcast as a Master, then the {@code copy_relationships} rows themselves ({@code
   * management_agreements} auto-cascades from that last step). The caller must already have deleted
   * any {@code performance_fee_ledger} rows referencing these relationships first (see {@code
   * billing.api.PerformanceFeeLedgerArchivalApi}) — that table also has no cascade.
   */
  void deleteForBrokerAccount(UUID brokerAccountId);
}
