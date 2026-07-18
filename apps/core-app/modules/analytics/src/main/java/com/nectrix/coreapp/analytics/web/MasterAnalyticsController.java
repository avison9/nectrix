package com.nectrix.coreapp.analytics.web;

import com.nectrix.coreapp.analytics.repository.MasterAnalyticsRepository.OwnedMasterProfileRef;
import com.nectrix.coreapp.analytics.service.MasterAnalyticsService;
import com.nectrix.coreapp.analytics.service.MasterAnalyticsService.MasterAnalytics;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-116 — the mock's Master Analytics screen. Ownership is enforced entirely by {@link
 * MasterAnalyticsService#getOwnedMasterProfile}'s own {@code @PostAuthorize} — no
 * {@code @AuthenticationPrincipal Jwt} needed here at all (unlike list endpoints that scope a query
 * by caller id, this one targets one specific {@code id} and lets method security reject it).
 */
@RestController
public class MasterAnalyticsController {

  private final MasterAnalyticsService service;

  public MasterAnalyticsController(MasterAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/master-profiles/{id}/analytics")
  public MasterAnalytics analytics(
      @PathVariable UUID id, @RequestParam(defaultValue = "30D") String period) {
    OwnedMasterProfileRef profile = service.getOwnedMasterProfile(id);
    return service.computeAnalytics(profile, period);
  }
}
