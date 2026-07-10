package com.nectrix.coreapp.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TICKET-101 — authenticates {@code /internal/**} requests via a static shared-secret header
 * instead of a JWT bearer token; the ONLY caller is apps/broker-adapters (Go), which has no user
 * identity to present as a JWT subject. Reachability is primarily restricted by a K8s NetworkPolicy
 * (deploy/base/core-app) limiting {@code /internal/**} to in-cluster callers — this header check is
 * defense in depth on top of that, not the sole guard, mirroring apps/broker-adapters' own
 * internalapi package doing the same check in the other direction.
 *
 * <p>On a valid header, populates the SecurityContext with a minimal authenticated principal (no
 * username/roles beyond {@code ROLE_INTERNAL_SERVICE} — nothing here is per-user) so the paired
 * {@code SecurityFilterChain}'s {@code anyRequest().authenticated()} matcher is satisfied. On a
 * missing/mismatched header, responds 401 immediately and does not continue the chain — no fallback
 * to any other authentication mechanism.
 */
public class InternalServiceTokenFilter extends OncePerRequestFilter {

  private static final String HEADER_NAME = "X-Internal-Service-Token";

  private final String expectedToken;

  public InternalServiceTokenFilter(String expectedToken) {
    this.expectedToken = expectedToken;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String presentedToken = request.getHeader(HEADER_NAME);
    if (expectedToken == null
        || expectedToken.isEmpty()
        || presentedToken == null
        || !constantTimeEquals(expectedToken, presentedToken)) {
      response.sendError(
          HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid internal service token");
      return;
    }

    var authorities = List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"));
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated("internal-service", null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    filterChain.doFilter(request, response);
  }

  /** MessageDigest.isEqual is constant-time — avoids a timing side channel on the comparison. */
  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
