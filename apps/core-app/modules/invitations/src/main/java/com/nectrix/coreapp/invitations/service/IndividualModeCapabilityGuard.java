package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.billing.api.CapabilityLimitsApi;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-114 — shared by {@link BrokerLinkingService}/{@link MtLinkingService}, the two places a
 * new {@code broker_accounts} row is ever inserted. A real Master (admin-provisioned {@code MASTER}
 * role) or real Follower (invite-created {@code FOLLOWER} role) is never subject to this limit —
 * only a caller holding neither role (Individual mode) has their master-slot/follower-slot capacity
 * checked against {@link CapabilityLimitsApi}, which itself returns {@code 0} for a user with no
 * active/trialing subscription (no implicit free tier).
 */
@Service
class IndividualModeCapabilityGuard {

  private final CapabilityLimitsApi capabilityLimitsApi;
  private final BrokerAccountRepository repository;

  IndividualModeCapabilityGuard(
      CapabilityLimitsApi capabilityLimitsApi, BrokerAccountRepository repository) {
    this.capabilityLimitsApi = capabilityLimitsApi;
    this.repository = repository;
  }

  /**
   * @param callerRoles the caller's {@code roles} JWT claim.
   * @param requestedConnectionRole the already-resolved (never null) connection_role of the account
   *     about to be inserted.
   */
  void check(UUID userId, List<String> callerRoles, String requestedConnectionRole) {
    if (callerRoles != null
        && (callerRoles.contains("MASTER") || callerRoles.contains("FOLLOWER"))) {
      return;
    }
    checkSlot(
        userId, requestedConnectionRole, "MASTER_ONLY", capabilityLimitsApi.maxMasterSlots(userId));
    checkSlot(
        userId,
        requestedConnectionRole,
        "FOLLOWER_ONLY",
        capabilityLimitsApi.maxFollowerSlots(userId));
  }

  private void checkSlot(UUID userId, String requestedRole, String targetRole, int limit) {
    boolean appliesToTarget = targetRole.equals(requestedRole) || "BOTH".equals(requestedRole);
    if (!appliesToTarget) {
      return;
    }
    int current = repository.countForUserByConnectionRole(userId, targetRole);
    if (current >= limit) {
      throw new BrokerAccountLimitExceededException(targetRole, limit);
    }
  }
}
