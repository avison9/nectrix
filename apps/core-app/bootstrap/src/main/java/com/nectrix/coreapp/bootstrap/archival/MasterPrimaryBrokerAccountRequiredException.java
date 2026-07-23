package com.nectrix.coreapp.bootstrap.archival;

import java.util.UUID;

/**
 * Bugfix — thrown by {@link BrokerAccountArchivalOrchestrator#archiveAndDelete} when the account
 * being archived is currently a Master's {@code primary_broker_account_id} and no other eligible
 * (CONNECTED, Master-capable {@code connectionRole}) broker account of theirs exists to
 * auto-reassign to first. Distinct from the generic {@code IllegalStateException} the same method
 * throws for a still-CONNECTED account, so the frontend can show a real, actionable message ("link
 * another broker account first") instead of the misleading "disconnect this account" one —
 * disconnecting doesn't fix this case, since the account already is disconnected.
 */
public class MasterPrimaryBrokerAccountRequiredException extends RuntimeException {

  public MasterPrimaryBrokerAccountRequiredException(UUID brokerAccountId) {
    super(
        "Broker account "
            + brokerAccountId
            + " is a Master's only eligible primary broker account — link another before"
            + " deleting this one");
  }
}
