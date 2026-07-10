package com.nectrix.coreapp.invitations.client;

import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * TICKET-101 — calls apps/broker-adapters' internal-only {@code POST /internal/ctrader/accounts}
 * (see that service's internalapi package) to list the trading accounts an OAuth access token
 * grants access to. Java can't do this itself: cTrader's account-listing call
 * (ProtoOAGetAccountListByAccessTokenReq) only exists over the Protobuf/TLS connection Go owns, not
 * as a plain REST call — this is the one and only reason this HTTP hop exists.
 */
@Service
public class BrokerAdaptersInternalClient {

  private final RestClient restClient = RestClient.create();
  private final InvitationsProperties.BrokerAdapters config;

  public BrokerAdaptersInternalClient(InvitationsProperties props) {
    this.config = props.brokerAdapters();
  }

  public List<CtraderAccount> listAccountsByAccessToken(String accessToken) {
    CtraderAccount[] accounts =
        restClient
            .post()
            .uri(config.internalBaseUrl() + "/internal/ctrader/accounts")
            .header("X-Internal-Service-Token", config.serviceToken())
            .body(new ListAccountsRequest(accessToken))
            .retrieve()
            .body(CtraderAccount[].class);
    return accounts == null ? List.of() : List.of(accounts);
  }

  private record ListAccountsRequest(String accessToken) {}

  public record CtraderAccount(
      long ctidTraderAccountId, boolean isLive, long traderLogin, String brokerTitleShort) {}
}
