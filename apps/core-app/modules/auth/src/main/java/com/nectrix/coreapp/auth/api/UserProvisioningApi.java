package com.nectrix.coreapp.auth.api;

import java.util.UUID;

/**
 * The only cross-module-sanctioned surface of the auth module (enforced by ModuleBoundaryArchTest —
 * other modules may depend on {@code auth.api}, never {@code auth.repository}/{@code auth.domain}
 * directly). No self-registration exists anywhere in this platform (docs/05-domain-model.md §5.0) —
 * this is the one function every account-creation path (TICKET-012's admin-provisioning endpoint,
 * Phase 1's accept-invite endpoint) calls into. Deliberately uses only plain Java/domain types
 * (String, UUID) in its signature, never a Spring type, so consumers of this interface never need
 * Spring Security/JDBC/etc. on their own classpath.
 */
public interface UserProvisioningApi {

  /**
   * @param rawPassword nullable — OAuth-provisioned or invite-accepted-without-password-yet users
   *     may have none initially; hashed internally, never stored/logged raw.
   * @param createdByUserId nullable — the Admin/Support user who provisioned this account (null
   *     only for the initial bootstrap admin).
   * @param createdViaInvitationId nullable — set for Follower accounts created by accepting an
   *     invitation (not yet a real code path in Phase 0 — invitations don't exist until Phase 1's
   *     accept-invite endpoint, which will pass this through).
   * @param referredByUserId nullable — platform-level Partner/IB referral attribution.
   * @return the new user's id.
   */
  UUID createUser(
      String email,
      String rawPassword,
      String displayName,
      UUID createdByUserId,
      UUID createdViaInvitationId,
      UUID referredByUserId,
      String region);
}
