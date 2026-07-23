package com.nectrix.coreapp.trading.client;

import com.nectrix.coreapp.trading.config.TradingProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * TICKET-124 — calls apps/copy-engine's internal-only {@code POST
 * /internal/pnl/unrealized-batch} (see that service's {@code internal/httpapi} package) to
 * compute unrealized P&L for open positions, reusing the exact same {@code computeRealizedPnL}
 * formula/DB reads {@code copy-engine} already uses at close time — never a second, independently
 * maintained computation of the same figure.
 */
@Service
public class CopyEngineInternalClient {

  private final RestClient restClient = RestClient.create();
  private final TradingProperties.CopyEngine config;

  public CopyEngineInternalClient(TradingProperties props) {
    this.config = props.copyEngine();
  }

  public List<UnrealizedPnlResult> computeUnrealizedPnlBatch(List<UnrealizedPnlItem> items) {
    if (items.isEmpty()) {
      return List.of();
    }
    UnrealizedPnlResult[] results =
        restClient
            .post()
            .uri(config.internalBaseUrl() + "/internal/pnl/unrealized-batch")
            .header("X-Internal-Service-Token", config.serviceToken())
            .body(items)
            .retrieve()
            .body(UnrealizedPnlResult[].class);
    return results == null ? List.of() : List.of(results);
  }

  /** Mirrors copy-engine's own pipeline.UnrealizedPnLItem JSON shape exactly. */
  public record UnrealizedPnlItem(
      String id,
      String followerBrokerAccountId,
      String canonicalSymbol,
      String assetClass,
      String direction,
      double volumeLots,
      double openPrice,
      double currentPrice) {}

  /** Mirrors copy-engine's own pipeline.UnrealizedPnLResult JSON shape exactly. */
  public record UnrealizedPnlResult(String id, Double unrealizedPnl) {}
}
