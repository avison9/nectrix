package com.nectrix.coreapp.social.web;

import com.nectrix.coreapp.social.domain.MasterProfile;
import com.nectrix.coreapp.social.service.MasterProfileService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MasterProfileController {

  private final MasterProfileService service;

  public MasterProfileController(MasterProfileService service) {
    this.service = service;
  }

  /**
   * {@code hasRole('MASTER')} — role-gated, not ownership-gated (there's nothing to own yet), same
   * convention {@code AdminController} established for this kind of check. A {@code FOLLOWER}-only
   * caller gets Spring Security's own 403 automatically, no custom exception handling needed.
   */
  @PreAuthorize("hasRole('MASTER')")
  @PostMapping("/api/v1/master-profiles")
  public MasterProfile create(
      @AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
    return service.create(
        currentUserId(jwt),
        request.brokerAccountId(),
        request.displayName(),
        request.bio(),
        request.strategyTags(),
        request.performanceFeePercent(),
        request.feeCollectionMethod());
  }

  @GetMapping("/api/v1/master-profiles/{id}")
  public MasterProfile getById(@PathVariable UUID id) {
    return service.getMasterProfile(id);
  }

  /**
   * TICKET-116 — the caller's own profile, by their own JWT subject. Registered as a distinct
   * {@code @GetMapping} rather than folded into {@link #getById}'s {@code {id}} path variable —
   * Spring's path-pattern matching prioritizes this literal {@code /me} segment over the variable
   * one, so both routes coexist safely (same precedent {@code CopyRelationshipController}'s own
   * {@code /copy-relationships/trades} vs {@code /copy-relationships/{id}} already established).
   */
  @GetMapping("/api/v1/master-profiles/me")
  public MasterProfile getMyProfile(@AuthenticationPrincipal Jwt jwt) {
    return service.getMyProfile(currentUserId(jwt));
  }

  @PatchMapping("/api/v1/master-profiles/{id}")
  public MasterProfile patch(@PathVariable UUID id, @RequestBody PatchRequest request) {
    // Explicit fetch-then-check-then-mutate — see MasterProfileService's own Javadoc for why the
    // ownership check can't live inside the mutating method itself.
    MasterProfile existing = service.getMasterProfile(id);
    return service.updateSettings(
        existing,
        request.displayName(),
        request.bio(),
        request.strategyTags(),
        request.performanceFeePercent(),
        request.isPublic());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record CreateRequest(
      UUID brokerAccountId,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod) {}

  public record PatchRequest(
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      Boolean isPublic) {}
}
