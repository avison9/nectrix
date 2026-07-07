package com.nectrix.coreapp.auth.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * TICKET-006's fine-grained, object-level authorization primitive — the IDOR-prevention layer
 * called out in docs/17-security-architecture.md §17.3. Exposed as a named bean ({@code "perms"})
 * so it can be referenced from any module's {@code @PostAuthorize}/{@code @PreAuthorize} SpEL
 * expressions purely by bean name (e.g.
 * {@code @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")}) — this is
 * a *runtime* bean-name lookup, not a compile-time class import, so it creates no cross-module Java
 * dependency and stays outside the ArchUnit module-boundary rule's scope entirely. One
 * implementation here, reused by every future per-user-owned resource (BrokerAccount today;
 * CopyRelationship/Invitation/BrokerIBLink/ BrokerFeeReport later) instead of re-implementing the
 * same ownership check ad hoc per endpoint.
 */
@Component("perms")
public class SecurityPermissions {

  /**
   * True if the caller owns the resource ({@code ownerId} matches the JWT's {@code sub} claim), or
   * is platform staff (ADMIN/SUPPORT — docs/12-analytics-notifications-admin.md §12.3: staff can
   * view any Master/Follower's resources, not just their own).
   */
  public boolean isOwnerOrStaff(Authentication authentication, UUID ownerId) {
    boolean isStaff =
        authentication.getAuthorities().stream()
            .anyMatch(
                authority ->
                    authority.getAuthority().equals("ROLE_ADMIN")
                        || authority.getAuthority().equals("ROLE_SUPPORT"));
    if (isStaff) {
      return true;
    }
    return authentication.getPrincipal() instanceof Jwt jwt
        && jwt.getSubject().equals(ownerId.toString());
  }
}
