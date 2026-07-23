package com.nectrix.coreapp.trading.api;

import java.util.UUID;

/**
 * Bugfix — cross-module-sanctioned surface letting {@code bootstrap}'s {@code
 * MasterPrimaryBrokerAccountOrchestrator} cascade a Master's primary-broker-account change into any
 * of their existing, non-terminal {@code copy_relationships} rows, without importing {@code
 * trading.repository} directly (enforced by ModuleBoundaryArchTest). No ownership check here — same
 * "system-trusted caller, not a per-request principal" reasoning {@code BrokerAccountArchivalApi}'s
 * own Javadoc gives: the orchestrator itself already either validated the real caller (the
 * on-demand self-service endpoint) or is the scheduled archival sweep (no caller at all).
 */
public interface CopyRelationshipMasterAccountApi {

  void reassignMasterBrokerAccount(
      UUID masterProfileId, UUID oldBrokerAccountId, UUID newBrokerAccountId);
}
