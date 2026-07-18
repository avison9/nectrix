package com.nectrix.coreapp.invitations.client;

import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * TICKET-101 — calls apps/broker-adapters' internal-only {@code POST /internal/ctrader/accounts}
 * (see that service's internalapi package) to list the trading accounts an OAuth access token
 * grants access to. Java can't do this itself: cTrader's account-listing call
 * (ProtoOAGetAccountListByAccessTokenReq) only exists over the Protobuf/TLS connection Go owns, not
 * as a plain REST call — this is the one and only reason this HTTP hop exists.
 *
 * <p>TICKET-110 widens this with {@link #getAccountSnapshot}/{@link #getOpenPositions}, plain
 * passthroughs to whichever Go service owns the account's live broker connection — cTrader routes
 * to apps/broker-adapters, MT5/MT4 to apps/mt5-bridge-gateway (both already expose the identical
 * {@code GET .../accounts/{id}/snapshot}/{@code .../positions} routes, landed in TICKET-106/109).
 */
@Service
public class BrokerAdaptersInternalClient {

  private final RestClient restClient = RestClient.create();
  private final InvitationsProperties.BrokerAdapters brokerAdaptersConfig;
  private final InvitationsProperties.MtBridge mtBridgeConfig;

  public BrokerAdaptersInternalClient(InvitationsProperties props) {
    this.brokerAdaptersConfig = props.brokerAdapters();
    this.mtBridgeConfig = props.mtBridge();
  }

  public List<CtraderAccount> listAccountsByAccessToken(String accessToken) {
    CtraderAccount[] accounts =
        restClient
            .post()
            .uri(brokerAdaptersConfig.internalBaseUrl() + "/internal/ctrader/accounts")
            .header("X-Internal-Service-Token", brokerAdaptersConfig.serviceToken())
            .body(new ListAccountsRequest(accessToken))
            .retrieve()
            .body(CtraderAccount[].class);
    return accounts == null ? List.of() : List.of(accounts);
  }

  public AccountSnapshot getAccountSnapshot(String brokerType, String brokerAccountId) {
    Route route = routeFor(brokerType);
    return restClient
        .get()
        .uri(
            route.baseUrl()
                + "/internal/"
                + route.pathPrefix()
                + "/accounts/"
                + brokerAccountId
                + "/snapshot")
        .header("X-Internal-Service-Token", route.serviceToken())
        .retrieve()
        .body(AccountSnapshot.class);
  }

  public List<NormalizedPosition> getOpenPositions(String brokerType, String brokerAccountId) {
    Route route = routeFor(brokerType);
    NormalizedPosition[] positions =
        restClient
            .get()
            .uri(
                route.baseUrl()
                    + "/internal/"
                    + route.pathPrefix()
                    + "/accounts/"
                    + brokerAccountId
                    + "/positions")
            .header("X-Internal-Service-Token", route.serviceToken())
            .retrieve()
            .body(NormalizedPosition[].class);
    return positions == null ? List.of() : List.of(positions);
  }

  /**
   * TICKET-116 — the manual symbol-mapping fallback: verifies a user-typed broker symbol name
   * against a live broker round trip (ResolveSymbol + GetSymbolSpecification, both account-agnostic
   * — see apps/broker-adapters' own {@code internalapi.handleResolveSymbol} Javadoc) and returns
   * its real spec. Empty means the broker didn't recognize the symbol (the Go route's own 404) — a
   * legitimate, expected outcome the caller maps to {@code UnresolvedBrokerSymbolException}, not a
   * broker-adapter-is-down failure (which still propagates as an unchecked exception).
   *
   * <p>MT5/MT4 share one deployment/route ({@code mt}), so unlike {@link #getAccountSnapshot}/
   * {@link #getOpenPositions} this appends {@code ?platform=} explicitly —
   * apps/mt5-bridge-gateway's own {@code handleResolveSymbol} requires it to pick which of its two
   * adapters to call.
   *
   * <p>Built via {@link UriComponentsBuilder}'s template-expansion (not string concatenation,
   * unlike this class's other methods) — {@code brokerSymbolName} is user-typed and broker
   * symbol-naming conventions really do include characters string concatenation would mangle (e.g.
   * {@code go-domain}'s own {@code CandidateBrokerSymbolNames} catalog lists a {@code "#" + code}
   * form), so the path segment needs real percent-encoding, not a raw append.
   */
  public Optional<SymbolSpec> resolveSymbol(
      String brokerType, String brokerAccountId, String brokerSymbolName) {
    Route route = routeFor(brokerType);
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString(
            route.baseUrl()
                + "/internal/"
                + route.pathPrefix()
                + "/accounts/{brokerAccountId}/symbols/{symbol}/resolve");
    if (!"ctrader".equals(route.pathPrefix())) {
      builder.queryParam("platform", brokerType);
    }
    URI uri = builder.buildAndExpand(brokerAccountId, brokerSymbolName).encode().toUri();
    try {
      SymbolSpec spec =
          restClient
              .get()
              .uri(uri)
              .header("X-Internal-Service-Token", route.serviceToken())
              .retrieve()
              .body(SymbolSpec.class);
      return Optional.ofNullable(spec);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  /** Mirrors go-domain's SymbolSpec JSON shape exactly (already camelCase on the wire). */
  public record SymbolSpec(
      NormalizedSymbol symbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      int digits,
      String marginCurrency) {}

  /**
   * cTrader has its own dedicated deployment (apps/broker-adapters); MT5 and MT4 both live behind
   * apps/mt5-bridge-gateway's shared {@code /internal/mt} prefix (mirrors
   * apps/copy-engine/internal/remoteadapter/router.go's own per-broker-type routing).
   */
  private Route routeFor(String brokerType) {
    return switch (brokerType) {
      case "CTRADER" ->
          new Route(
              brokerAdaptersConfig.internalBaseUrl(),
              "ctrader",
              brokerAdaptersConfig.serviceToken());
      case "MT5", "MT4" ->
          new Route(mtBridgeConfig.internalBaseUrl(), "mt", mtBridgeConfig.serviceToken());
      default -> throw new IllegalArgumentException("Unknown brokerType: " + brokerType);
    };
  }

  private record Route(String baseUrl, String pathPrefix, String serviceToken) {}

  private record ListAccountsRequest(String accessToken) {}

  public record CtraderAccount(
      long ctidTraderAccountId, boolean isLive, long traderLogin, String brokerTitleShort) {}

  /** Mirrors go-domain's AccountSnapshot JSON shape exactly (already camelCase on the wire). */
  public record AccountSnapshot(
      String brokerAccountId,
      String currency,
      double balance,
      double equity,
      double usedMargin,
      double freeMargin,
      Double marginLevelPct,
      String asOf) {}

  /** Mirrors go-domain's NormalizedPosition JSON shape exactly. */
  public record NormalizedPosition(
      String brokerPositionId,
      NormalizedSymbol symbol,
      String direction,
      double volumeLots,
      double openPrice,
      Double currentSlPrice,
      Double currentTpPrice,
      String openedAt) {}

  public record NormalizedSymbol(String canonicalCode, String assetClass) {}
}
