package com.nectrix.coreapp.analytics.web;

import com.nectrix.coreapp.analytics.service.FollowerAnalyticsService;
import com.nectrix.coreapp.analytics.service.FollowerAnalyticsService.FollowerAnalytics;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feature — the Follower analytics page's real backend (previously 100% hardcoded sample data on
 * the frontend, never wired to anything). Self-scoped by the caller's own JWT subject (same
 * convention {@code CopyRelationshipController#list}'s {@code role=follower} query already uses),
 * unlike {@code MasterAnalyticsController}'s per-{@code id} route — there's no equivalent "someone
 * else's follower analytics" staff use case to gate here.
 */
@RestController
public class FollowerAnalyticsController {

  private final FollowerAnalyticsService service;

  public FollowerAnalyticsController(FollowerAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/followers/me/analytics")
  public FollowerAnalytics analytics(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "30D") String period) {
    UUID followerUserId = UUID.fromString(jwt.getSubject());
    return service.computeAnalytics(followerUserId, period);
  }
}
