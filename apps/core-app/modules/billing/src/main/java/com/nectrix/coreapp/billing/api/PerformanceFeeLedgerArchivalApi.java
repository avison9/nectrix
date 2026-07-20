package com.nectrix.coreapp.billing.api;

import java.util.List;
import java.util.UUID;

/**
 * TICKET-101 follow-up — cross-module-sanctioned surface for {@code bootstrap}'s {@code
 * BrokerAccountArchivalOrchestrator}, which needs to export-then-delete this module's own {@code
 * performance_fee_ledger} rows (and export, but never delete, {@code management_agreements} — see
 * {@link #deleteForRelationships}'s own Javadoc) for a set of {@code copy_relationships} being
 * permanently deleted, without importing {@code billing.repository} directly. Same convention as
 * {@code trading.api.CopyRelationshipArchivalApi}.
 */
public interface PerformanceFeeLedgerArchivalApi {

  /**
   * Read-only — must be called (and its result durably archived) before {@link
   * #deleteForRelationships}.
   */
  PerformanceFeeLedgerArchiveExport findForExport(List<UUID> copyRelationshipIds);

  /**
   * Deletes {@code performance_fee_ledger} rows for the given relationship ids only — this table
   * has no {@code ON DELETE CASCADE} of its own, so the caller must run this BEFORE deleting the
   * {@code copy_relationships} rows themselves (see {@code
   * trading.api.CopyRelationshipArchivalApi#deleteForBrokerAccount}'s own ordering Javadoc). {@code
   * management_agreements} is never deleted here — it already cascades automatically once {@code
   * copy_relationships} is deleted, so this API only ever exports it (see {@link #findForExport}).
   */
  void deleteForRelationships(List<UUID> copyRelationshipIds);
}
