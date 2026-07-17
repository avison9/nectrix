package com.nectrix.coreapp.billing.api;

import java.util.UUID;

/**
 * TICKET-114 — cross-module-sanctioned surface letting {@code invitations} enforce the
 * self-directed "Individual" mode's master-slot/follower-slot capacity (how many of a user's own
 * {@code broker_accounts} may be {@code MASTER_ONLY}/{@code FOLLOWER_ONLY}) without importing
 * {@code billing.service}/{@code billing.repository} directly — same precedent {@code
 * invitations.api.BrokerAccountLookupApi} already established for {@code social}'s dependency on
 * {@code invitations}.
 *
 * <p>Returns {@code 0} for a user with no active/trialing subscription — there is no implicit free
 * tier (TICKET-114 requires a card on file for every plan, including the entry one). This only
 * matters for Individual-mode users in the first place: real Masters (admin-provisioned) and real
 * Followers (invite-created) are never subject to these limits at all — callers are responsible for
 * only checking this for a caller who holds neither the {@code MASTER} nor {@code FOLLOWER} role.
 */
public interface CapabilityLimitsApi {

  int maxMasterSlots(UUID userId);

  int maxFollowerSlots(UUID userId);
}
