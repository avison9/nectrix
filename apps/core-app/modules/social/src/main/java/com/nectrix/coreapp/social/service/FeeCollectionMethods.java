package com.nectrix.coreapp.social.service;

import java.util.Set;

/**
 * Shared validation for {@code master_profiles.fee_collection_method}'s own CHECK constraint values
 * (005-social-marketplace.sql) — same "surface a clean 400 before ever reaching the DB constraint"
 * convention {@code ConnectionRoles} established in {@code modules:invitations}.
 */
final class FeeCollectionMethods {

  static final String DEFAULT = "BROKER_PARTNERSHIP";

  private static final Set<String> VALID = Set.of("BROKER_PARTNERSHIP", "STRIPE_INVOICE");

  private FeeCollectionMethods() {}

  /** Returns {@code DEFAULT} for {@code null}, else validates. */
  static String resolveOrDefault(String feeCollectionMethod) {
    if (feeCollectionMethod == null) {
      return DEFAULT;
    }
    if (!VALID.contains(feeCollectionMethod)) {
      throw new InvalidFeeCollectionMethodException();
    }
    return feeCollectionMethod;
  }
}
