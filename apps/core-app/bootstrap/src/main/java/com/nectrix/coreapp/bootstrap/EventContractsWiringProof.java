package com.nectrix.coreapp.bootstrap;

import com.nectrix.events.v1.NormalizedTradeEvent;

/**
 * Proves the packages/event-contracts/java composite build is correctly wired into core-app — real
 * event production/consumption lands with the modules that need it (billing, notifications, etc. in
 * later tickets).
 */
final class EventContractsWiringProof {
  private EventContractsWiringProof() {}

  static boolean eventContractsAvailable() {
    return NormalizedTradeEvent.getDefaultInstance() != null;
  }
}
