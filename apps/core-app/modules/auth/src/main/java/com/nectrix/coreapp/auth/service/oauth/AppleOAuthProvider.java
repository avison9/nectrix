package com.nectrix.coreapp.auth.service.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nectrix.coreapp.auth.config.AuthProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Implemented but NOT end-to-end tested against Apple's real servers — requires a paid Apple
 * Developer Program membership, documented as a known gap (same treatment as TICKET-011's
 * 2FA-encryption stub). Structurally identical to {@link GoogleOAuthProvider} (RS256 ID token,
 * JWKS-verified) with two real Apple-specific quirks worth flagging for whoever wires up real
 * credentials later:
 *
 * <ul>
 *   <li>Apple's {@code client_secret} is not a static string — it's a JWT signed with your Apple
 *       Developer private key (ES256), valid for at most 6 months and needing periodic
 *       regeneration. This class accepts an already-generated secret via config ({@code
 *       APPLE_OAUTH_CLIENT_SECRET}) rather than generating/rotating it here — out of scope until
 *       real Apple credentials exist to test the full lifecycle against.
 *   <li>Apple only includes the user's {@code email} claim on their *first* authorization for your
 *       app — subsequent logins may omit it entirely. Since this OAuth path is login-only (never
 *       creates an account) and needs the email to look up an existing user, a missing email on a
 *       repeat Apple login would leave no way to identify the account through this exchange alone.
 *       Unresolved here; flagged for whoever activates this path for real.
 * </ul>
 */
@Service
public class AppleOAuthProvider implements OAuthProvider {

  private static final String TOKEN_ENDPOINT = "https://appleid.apple.com/auth/token";
  private static final String JWKS_URI = "https://appleid.apple.com/auth/keys";
  private static final List<String> ACCEPTED_ISSUERS = List.of("https://appleid.apple.com");

  private final RestClient restClient = RestClient.create();
  private final AuthProperties.Provider config;
  private final OidcIdTokenVerifier verifier;

  public AppleOAuthProvider(AuthProperties props) {
    this.config = props.oauth().apple();
    this.verifier = new OidcIdTokenVerifier(ACCEPTED_ISSUERS, JWKS_URI, config.clientId());
  }

  @Override
  public String exchangeCodeForVerifiedEmail(String authorizationCode) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("code", authorizationCode);
    form.add("client_id", config.clientId());
    form.add("client_secret", config.clientSecret());
    form.add("redirect_uri", config.redirectUri());
    form.add("grant_type", "authorization_code");

    TokenResponse tokenResponse =
        restClient
            .post()
            .uri(TOKEN_ENDPOINT)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    if (tokenResponse == null || tokenResponse.idToken() == null) {
      throw new IllegalStateException("Apple token exchange did not return an id_token");
    }

    JWTClaimsSet claims;
    try {
      claims = verifier.verify(tokenResponse.idToken());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to verify Apple ID token", e);
    }
    try {
      String email = claims.getStringClaim("email");
      if (email == null) {
        throw new IllegalStateException(
            "Apple ID token has no email claim (only sent on first authorization) — cannot identify account");
      }
      return email;
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("Apple ID token missing email claim", e);
    }
  }

  private record TokenResponse(
      @JsonProperty("id_token") String idToken, @JsonProperty("access_token") String accessToken) {}
}
