package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Feature — computes each relationship's % return since following, for the Master's own followers
 * list/detail view: {@code ((currentEquity - startingEquity) / startingEquity) * 100}, the same
 * formula shape {@code LeaderboardComputationService.computeReturnPct} already uses. One live
 * {@code getAccountBalance} call per DISTINCT follower broker account (never per relationship row —
 * a follower account can back more than one relationship), best-effort — a failed live call or a
 * relationship with no {@code startingEquity} (created before this shipped) is simply absent from
 * the result map, never a fabricated number. Deliberately never touches raw balance/equity itself,
 * only the computed percentage.
 */
@Service
public class ReturnPctEnrichmentService {

  private static final Logger log = LoggerFactory.getLogger(ReturnPctEnrichmentService.class);

  private final BrokerAccountLookupApi brokerAccountLookupApi;

  public ReturnPctEnrichmentService(BrokerAccountLookupApi brokerAccountLookupApi) {
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  /** Keyed by {@code CopyRelationship.id()} — absent means "no return% available." */
  public Map<UUID, BigDecimal> enrich(List<CopyRelationship> relationships) {
    List<CopyRelationship> eligible =
        relationships.stream()
            .filter(r -> r.startingEquity() != null && r.startingEquity().signum() != 0)
            .toList();
    if (eligible.isEmpty()) {
      return Map.of();
    }

    Map<UUID, BigDecimal> currentEquityByAccount = new HashMap<>();
    for (UUID accountId :
        eligible.stream().map(CopyRelationship::followerBrokerAccountId).distinct().toList()) {
      try {
        currentEquityByAccount.put(
            accountId, brokerAccountLookupApi.getAccountBalance(accountId).equity());
      } catch (Exception e) {
        log.warn(
            "returnPct enrichment: could not fetch live equity for broker account {}",
            accountId,
            e);
        // best-effort — this account's relationships simply show no return% this page load
      }
    }

    Map<UUID, BigDecimal> result = new HashMap<>();
    for (CopyRelationship r : eligible) {
      BigDecimal currentEquity = currentEquityByAccount.get(r.followerBrokerAccountId());
      if (currentEquity == null) {
        continue;
      }
      BigDecimal returnPct =
          currentEquity
              .subtract(r.startingEquity())
              .multiply(BigDecimal.valueOf(100))
              .divide(r.startingEquity(), 4, RoundingMode.HALF_UP);
      result.put(r.id(), returnPct);
    }
    return result;
  }
}
