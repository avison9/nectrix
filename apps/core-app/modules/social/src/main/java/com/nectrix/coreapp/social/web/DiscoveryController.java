package com.nectrix.coreapp.social.web;

import com.nectrix.coreapp.social.repository.DiscoveryRepository.LeaderboardEntry;
import com.nectrix.coreapp.social.repository.DiscoveryRepository.MasterPublicProfile;
import com.nectrix.coreapp.social.service.DiscoveryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-112 — public discovery surface (docs/14-api-specification.md §14.4). Both routes are
 * {@code permitAll()} in {@code SecurityConfig} — no {@code @PreAuthorize}/ownership checks
 * anywhere in this file, by design.
 *
 * <p>{@code GET /discovery/masters/{id}} isn't literally enumerated in docs/14 §14.4's endpoint
 * list, but is required by this ticket's own "public master profile page" scope — same "judged,
 * documented, minimal addition beyond the letter of the API spec" precedent as TICKET-111's {@code
 * GET /copy-relationships/{id}/trades}. It's deliberately a separate route from the existing,
 * authenticated, ownership-gated {@code GET /master-profiles/{id}} (that one's for a Master
 * managing their own profile; this one's for an anonymous visitor).
 */
@RestController
public class DiscoveryController {

  private final DiscoveryService service;

  public DiscoveryController(DiscoveryService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/discovery/leaderboard")
  public List<LeaderboardEntry> leaderboard(
      @RequestParam(defaultValue = "30D") String period,
      @RequestParam(defaultValue = "return_pct") String sort,
      @RequestParam(defaultValue = "0") int page) {
    return service.leaderboard(period, sort, page, 20);
  }

  @GetMapping("/api/v1/discovery/masters/{id}")
  public MasterPublicProfile masterProfile(@PathVariable UUID id) {
    return service.masterPublicProfile(id);
  }
}
