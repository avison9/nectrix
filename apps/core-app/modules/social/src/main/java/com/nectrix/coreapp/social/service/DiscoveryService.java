package com.nectrix.coreapp.social.service;

import com.nectrix.coreapp.social.repository.DiscoveryRepository;
import com.nectrix.coreapp.social.repository.DiscoveryRepository.LeaderboardEntry;
import com.nectrix.coreapp.social.repository.DiscoveryRepository.MasterPublicProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-112 — public discovery reads (docs/14-api-specification.md §14.4: "Discovery endpoints
 * remain public/unauthenticated"). No auth/ownership checks anywhere in this class — that's the
 * point.
 */
@Service
public class DiscoveryService {

  private final DiscoveryRepository repository;

  public DiscoveryService(DiscoveryRepository repository) {
    this.repository = repository;
  }

  public List<LeaderboardEntry> leaderboard(String period, String sort, int page, int pageSize) {
    return repository.findLeaderboard(period, sort, page, pageSize);
  }

  public MasterPublicProfile masterPublicProfile(UUID id) {
    return repository.findMasterPublicProfile(id).orElseThrow(MasterProfileNotFoundException::new);
  }
}
