package com.nectrix.coreapp.social.api;

import java.util.UUID;

/**
 * Bugfix — {@link MasterProfileLookupApi#changePrimaryBrokerAccount}'s result, carrying both the
 * OLD and NEW broker account id so the caller (bootstrap's orchestrator) can cascade the change
 * into any existing {@code copy_relationships} rows still pointing at the old one.
 */
public record PrimaryBrokerAccountChange(
    UUID masterProfileId, UUID oldBrokerAccountId, UUID newBrokerAccountId) {}
