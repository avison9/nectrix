package com.nectrix.coreapp.auth.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The only cross-module-sanctioned surface of the auth module (enforced by ModuleBoundaryArchTest —
 * other modules may depend on {@code auth.api}, never {@code auth.repository}/{@code auth.domain}
 * directly). This is the one function every account-creation path calls into: TICKET-012's
 * admin-provisioning endpoint, Phase 1's accept-invite endpoint (not yet built), and — as of
 * TICKET-114 — {@code RegistrationService}'s self-serve "Individual" registration, the one
 * deliberate exception to "no self-registration anywhere" (docs/05-domain-model.md §5.0), scoped
 * narrowly to that one path: {@code createdByUserId}/{@code createdViaInvitationId} both null, and
 * only the base {@code USER} role granted (never {@code MASTER}/{@code FOLLOWER} — those stay
 * admin-invite / master-invite / TICKET-122 tier-change-approval only). Deliberately uses only
 * plain Java/domain types (String, UUID) in its signature, never a Spring type, so consumers of
 * this interface never need Spring Security/JDBC/etc. on their own classpath.
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

  /**
   * Grants an additive role (docs/06-database-schema.md's {@code roles}/{@code user_roles}) —
   * TICKET-012's account-provisioning endpoint calls this right after {@link #createUser} so a
   * newly-provisioned Admin/Support account can actually reach the routes its role gates.
   * Idempotent (a repeat grant of the same role is a no-op, not an error).
   *
   * @param roleName must name an existing row in {@code roles} (FOLLOWER/MASTER/PARTNER/ADMIN/
   *     SUPPORT) — callers are responsible for validating this against whatever subset they mean to
   *     allow (e.g. the provisioning endpoint only permits ADMIN/SUPPORT); an unknown name is
   *     silently a no-op here, not an error.
   */
  void grantRole(UUID userId, String roleName);

  /**
   * TICKET-118 — {@code accept-invite}'s own "does this email already have an account?" check
   * (AC: accepting a second Master's invite for an already-registered email must not create a
   * second {@code User} row). Deliberately exact-match only, unlike {@code UserAdminApi#search}'s
   * substring/ILIKE behavior — this is an existence check, not a browse.
   */
  Optional<UUID> findUserIdByEmail(String email);
}
