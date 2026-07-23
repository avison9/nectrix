package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.trading.client.CopyEngineInternalClient;
import com.nectrix.coreapp.trading.domain.CopiedTrade;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository.FollowerAccountRef;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TICKET-124 — computes unrealized P&L for a page of {@link CopiedTrade} rows: fetches each
 * distinct follower broker account's real open positions (one {@code getOpenPositions} call per
 * account, not per row), matches them to OPEN/PARTIALLY_CLOSED trade rows by broker position id,
 * then hands the matched (openPrice, currentPrice) pairs to apps/copy-engine's {@code
 * ComputeUnrealizedPnLBatch} — reusing the exact same {@code computeRealizedPnL} formula/DB reads
 * copy-engine already uses at close time, never a second, independently maintained computation.
 *
 * <p>Both external calls (broker-adapters/mt5-bridge-gateway's own {@code getOpenPositions}, and
 * copy-engine's batch endpoint) are wrapped best-effort: a hiccup in either degrades to "no
 * unrealized P&L shown this page load," never a 500 on Trade History — same "one candidate's
 * failure must never abort the rest" philosophy {@code AccountSnapshotSchedulerJob} already
 * established, applied one layer further out (here, a failure is scoped to the accounts actually
 * affected, not the whole batch).
 */
@Service
public class UnrealizedPnlEnrichmentService {

  private static final Logger log = LoggerFactory.getLogger(UnrealizedPnlEnrichmentService.class);
  private static final List<String> OPEN_STATUSES = List.of("FILLED", "PARTIALLY_CLOSED");

  private final CopyRelationshipRepository copyRelationshipRepository;
  private final BrokerAdaptersInternalClient brokerAdaptersInternalClient;
  private final CopyEngineInternalClient copyEngineInternalClient;

  public UnrealizedPnlEnrichmentService(
      CopyRelationshipRepository copyRelationshipRepository,
      BrokerAdaptersInternalClient brokerAdaptersInternalClient,
      CopyEngineInternalClient copyEngineInternalClient) {
    this.copyRelationshipRepository = copyRelationshipRepository;
    this.brokerAdaptersInternalClient = brokerAdaptersInternalClient;
    this.copyEngineInternalClient = copyEngineInternalClient;
  }

  /**
   * Keyed by {@code CopiedTrade.id()} — absent from the map means "no unrealized P&L available."
   */
  public Map<UUID, BigDecimal> enrich(List<CopiedTrade> trades) {
    List<CopiedTrade> open =
        trades.stream()
            .filter(
                t ->
                    OPEN_STATUSES.contains(t.status())
                        && t.currentOpenVolumeLots() != null
                        && t.currentOpenVolumeLots().signum() > 0
                        && t.followerBrokerPositionId() != null
                        && t.filledPrice() != null)
            .toList();
    if (open.isEmpty()) {
      return Map.of();
    }

    List<UUID> relationshipIds =
        open.stream().map(CopiedTrade::copyRelationshipId).distinct().toList();
    Map<UUID, FollowerAccountRef> accountByRelationship =
        copyRelationshipRepository.findFollowerAccountRefs(relationshipIds).stream()
            .collect(Collectors.toMap(FollowerAccountRef::copyRelationshipId, r -> r));

    // One getOpenPositions call per DISTINCT (brokerAccountId, brokerType), not per trade row.
    // AssetClass isn't stored anywhere on copied_trades/trade_signals -- there is no
    // canonicalSymbol -> AssetClass lookup in this codebase independent of a broker's own
    // resolved symbol data, so it's captured here, straight from the same getOpenPositions call
    // that supplies currentPrice, rather than guessed.
    Map<String, LivePosition> liveByBrokerPositionId = new HashMap<>();
    accountByRelationship.values().stream()
        .map(FollowerAccountRef::followerBrokerAccountId)
        .distinct()
        .forEach(
            accountId -> {
              FollowerAccountRef ref =
                  accountByRelationship.values().stream()
                      .filter(r -> r.followerBrokerAccountId().equals(accountId))
                      .findFirst()
                      .orElseThrow();
              try {
                brokerAdaptersInternalClient
                    .getOpenPositions(ref.brokerType(), ref.followerBrokerAccountId().toString())
                    .forEach(
                        p -> {
                          if (p.currentPrice() != null) {
                            liveByBrokerPositionId.put(
                                p.brokerPositionId(),
                                new LivePosition(p.currentPrice(), p.symbol().assetClass()));
                          }
                        });
              } catch (Exception e) {
                log.warn(
                    "unrealized P&L: could not fetch open positions for broker account {}",
                    ref.followerBrokerAccountId(),
                    e);
                // best-effort -- this account's rows simply show no unrealized P&L this page load
              }
            });

    List<CopyEngineInternalClient.UnrealizedPnlItem> items =
        open.stream()
            .filter(t -> liveByBrokerPositionId.containsKey(t.followerBrokerPositionId()))
            .map(
                t -> {
                  FollowerAccountRef ref = accountByRelationship.get(t.copyRelationshipId());
                  LivePosition live = liveByBrokerPositionId.get(t.followerBrokerPositionId());
                  return new CopyEngineInternalClient.UnrealizedPnlItem(
                      t.id().toString(),
                      ref.followerBrokerAccountId().toString(),
                      t.canonicalSymbol(),
                      live.assetClass(),
                      t.direction(),
                      t.currentOpenVolumeLots().doubleValue(),
                      t.filledPrice().doubleValue(),
                      live.currentPrice());
                })
            .toList();
    if (items.isEmpty()) {
      return Map.of();
    }

    try {
      return copyEngineInternalClient.computeUnrealizedPnlBatch(items).stream()
          .filter(r -> r.unrealizedPnl() != null)
          .collect(
              Collectors.toMap(
                  r -> UUID.fromString(r.id()), r -> BigDecimal.valueOf(r.unrealizedPnl())));
    } catch (Exception e) {
      log.warn(
          "unrealized P&L: copy-engine batch call failed, showing no unrealized P&L this page load",
          e);
      return Map.of(); // best-effort -- never fail the whole Trade History page over this
    }
  }

  private record LivePosition(double currentPrice, String assetClass) {}
}
