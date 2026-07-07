package com.nectrix.coreapp.auth.api;

import java.util.UUID;

/**
 * TICKET-006's cross-module-sanctioned surface for admin/support impersonation — {@code admin}
 * module's controller calls this rather than importing {@code auth.service.JwtService} directly
 * (ArchUnit's {@code ModuleBoundaryRules} only restricts {@code auth.repository..}/{@code
 * auth.domain..}, not {@code auth.service..}, but going through {@code ..api..} anyway matches the
 * intended module-boundary discipline from docs/04-architecture-overview.md §4.4 — "Cross-module
 * reads go through a published interface... not direct calls" — same precedent as {@link
 * UserProvisioningApi}).
 */
public interface ImpersonationApi {

  /**
   * @param targetUserId the user being impersonated.
   * @param actingAdminId the ADMIN/SUPPORT user initiating the impersonation — embedded in the
   *     issued token's {@code impersonated_by} claim.
   * @return a short-lived access token for {@code targetUserId}.
   * @throws java.util.NoSuchElementException if {@code targetUserId} doesn't exist.
   */
  ImpersonationResult impersonate(UUID targetUserId, UUID actingAdminId);
}
