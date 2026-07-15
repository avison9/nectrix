package com.nectrix.coreapp.invitations.service;

import java.util.Set;

/**
 * Shared validation for the {@code broker_accounts.connection_role} CHECK constraint's own values —
 * used by both {@link BrokerLinkingService} and {@link MtLinkingService} at link time, and by
 * {@link BrokerAccountService} on PATCH, so a bad value surfaces as a clean 400 before ever
 * reaching the DB constraint.
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
