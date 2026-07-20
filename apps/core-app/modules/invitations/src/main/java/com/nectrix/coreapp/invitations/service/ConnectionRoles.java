package com.nectrix.coreapp.invitations.service;

import java.util.Set;

/**
 * Shared validation for the {@code broker_accounts.connection_role} CHECK constraint's own values —
 * used by both {@link BrokerLinkingService} and {@link MtLinkingService} at link time, and by
 * {@link BrokerAccountService} on PATCH, so a bad value surfaces as a clean 400 before ever
 * reaching the DB constraint.
 *
 * <p>Deliberately does NOT restrict which value a caller may pick based on their own MASTER/
 * FOLLOWER role — {@link IndividualModeCapabilityGuard}'s own existing, tested behavior already
 * allows an Individual-mode caller (neither role) to pick MASTER_ONLY or FOLLOWER_ONLY specifically
 * (tracked as separate per-role-type slot limits, not forced into BOTH), and a real Master/Follower
 * is never restricted to their own role's value either — nothing in this domain model actually
 * forbids e.g. a real Master also holding a separate Follower-only account. apps/web's own
 * broker-accounts/link/{ctrader,mt5} pages narrow the dropdown to one value per the caller's mode
 * as a UX simplification (same "hiding options is a UX nicety, not a real gate" precedent
 * CopyRelationshipActions' own comment establishes) — that's a client-side default, not a
 * server-enforced invariant.
 */
final class ConnectionRoles {

  static final String DEFAULT = "FOLLOWER_ONLY";

  private static final Set<String> VALID = Set.of("MASTER_ONLY", "FOLLOWER_ONLY", "BOTH");

  private ConnectionRoles() {}

  /** Returns {@code DEFAULT} for {@code null} (docs/07 §7.5's own default), else validates. */
  static String resolveOrDefault(String connectionRole) {
    if (connectionRole == null) {
      return DEFAULT;
    }
    if (!VALID.contains(connectionRole)) {
      throw new InvalidConnectionRoleException();
    }
    return connectionRole;
  }
}
