package com.nectrix.coreapp.admin.api;

import java.time.Instant;
import java.util.UUID;

/**
 * TICKET-117 — the cross-module surface {@code bootstrap}'s Kafka consumers write through, rather
 * than reaching {@code admin.repository} directly (enforced by {@code ModuleBoundaryArchTest} —
 * bootstrap is "outside the admin module" for this rule's purposes too, same as any other module).
 */
public interface AdminEventIngestApi {

  /** See {@code admin.repository.AdminRepository#insertReconciliationDrift}'s own Javadoc. */
  void recordReconciliationDrift(UUID brokerAccountId, String driftType, Instant detectedAt);
}
