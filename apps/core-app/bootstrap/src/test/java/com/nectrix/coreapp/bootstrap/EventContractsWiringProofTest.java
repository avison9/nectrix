package com.nectrix.coreapp.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventContractsWiringProofTest {
  @Test
  void eventContractsCompositeBuildIsWired() {
    assertTrue(EventContractsWiringProof.eventContractsAvailable());
  }
}
