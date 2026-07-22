package com.nectrix.coreapp.invitations.client;

import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * TICKET-101 — real cTrader Open API OAuth 2.0 code exchange. Modeled on modules/auth's
 * GoogleOAuthProvider (same {@code RestClient.create()} + form-urlencoded POST pattern), but
 * deliberately NOT an {@code OAuthProvider} implementation — that interface returns a verified
 * login *email*; this is a broker-account *linking* flow that returns broker tokens, a different
 * contract entirely.
 *
 * <p>Authorize endpoint/token endpoint/response shape confirmed against Spotware's own published
 * docs (connect.spotware.com/apps/auth, openapi.ctrader.com/apps/token — see
 * docs/07-auth-onboarding-broker-linking.md §7.6), not assumed.
 */
@Service
public class CTraderOAuthClient {

  private static final String AUTHORIZE_ENDPOINT = "https://connect.spotware.com/apps/auth";
  private static final String TOKEN_ENDPOINT = "https://openapi.ctrader.com/apps/token";

  private final RestClient restClient = RestClient.create();
  private final InvitationsProperties.CtraderOauth config;

  public CTraderOAuthClient(InvitationsProperties props) {
    this.config = props.ctraderOauth();
  }

  /**
   * Builds the URL the user's browser is redirected to, embedding our own CSRF {@code state}.
   *
   * <p>{@code client_id}/{@code client_secret} identify OUR app to Spotware, the same for every
   * Nectrix user — they are never how an individual end user's own cTrader account gets chosen.
   * Each end user authenticates directly with Spotware using their OWN cTrader ID/password (Nectrix
   * never sees those credentials), and the consent screen lists whichever accounts THAT login owns.
   * {@code prompt=login} forces Spotware to always show its own login form rather than silently
   * reusing an already-active Spotware browser session — without it, a browser that's still logged
   * into connect.spotware.com as one cTrader ID (e.g. from registering this app) would silently
   * re-authorize as that same account on every subsequent "Connect to cTrader" click, for any
   * Nectrix user, in that browser — never actually prompting a different end user to sign in as
   * themselves.
   */
  public String buildAuthorizeUrl(String state) {
    return AUTHORIZE_ENDPOINT
        + "?client_id="
        + encode(config.clientId())
        + "&redirect_uri="
        + encode(config.redirectUri())
        + "&scope="
        + encode("trading")
        + "&state="
        + encode(state)
        + "&prompt=login";
  }

  /** Exchanges an authorization code for real, usable OAuth tokens. */
  public TokenResponse exchangeCode(String authorizationCode) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", authorizationCode);
    form.add("redirect_uri", config.redirectUri());
    form.add("client_id", config.clientId());
    form.add("client_secret", config.clientSecret());

    TokenResponse response =
        restClient
            .post()
            .uri(TOKEN_ENDPOINT)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    if (response == null || response.accessToken() == null) {
      throw new IllegalStateException("cTrader token exchange did not return an accessToken");
    }
    return response;
  }

  /**
   * TICKET-101 task #120 — standard OAuth2 refresh grant against the SAME token endpoint (not the
   * Protobuf {@code ProtoOARefreshTokenReq}, which only exists over an already-established Open API
   * TCP connection apps/broker-adapters owns — this REST form is the one reachable without Go's
   * involvement, matching the plan's own "plain REST call" wording).
   */
  public TokenResponse refreshToken(String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "refresh_token");
    form.add("refresh_token", refreshToken);
    form.add("client_id", config.clientId());
    form.add("client_secret", config.clientSecret());

    TokenResponse response =
        restClient
            .post()
            .uri(TOKEN_ENDPOINT)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    if (response == null || response.accessToken() == null) {
      throw new IllegalStateException("cTrader token refresh did not return an accessToken");
    }
    return response;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Field names already camelCase on the wire — confirmed against cTrader's own token response. */
  public record TokenResponse(
      String accessToken, String refreshToken, String tokenType, Long expiresIn) {}
}
