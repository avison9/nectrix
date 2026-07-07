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
 * The only OAuth provider with real end-to-end testing for TICKET-005 (a free Google Cloud OAuth
 * 2.0 web-app client, redirect URI {@code http://localhost:8080/api/v1/auth/oauth/google/callback}
 * — see apps/core-app/README.md for setup). Not {@code spring-boot-starter-oauth2-client} — its
 * redirect-based {@code /oauth2/authorization/{id}} flow doesn't match this API's {@code POST
 * /oauth/{provider}/callback} JSON-in/JSON-out shape, so the code exchange is done directly here.
 */
@Service
public class GoogleOAuthProvider implements OAuthProvider {

  private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
  private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
  // Google issues either form depending on token version — OidcIdTokenVerifier
  // checks membership in this list, not a single exact match.
  private static final List<String> ACCEPTED_ISSUERS =
      List.of("https://accounts.google.com", "accounts.google.com");

  private final RestClient restClient = RestClient.create();
  private final AuthProperties.Provider config;
  private final OidcIdTokenVerifier verifier;

  public GoogleOAuthProvider(AuthProperties props) {
    this.config = props.oauth().google();
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
      throw new IllegalStateException("Google token exchange did not return an id_token");
    }

    JWTClaimsSet claims;
    try {
      claims = verifier.verify(tokenResponse.idToken());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to verify Google ID token", e);
    }

    Object emailVerified = claims.getClaim("email_verified");
    if (!(Boolean.TRUE.equals(emailVerified) || "true".equals(String.valueOf(emailVerified)))) {
      throw new IllegalStateException("Google account email is not verified");
    }
    try {
      return claims.getStringClaim("email");
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("Google ID token missing email claim", e);
    }
  }

  private record TokenResponse(
      @JsonProperty("id_token") String idToken, @JsonProperty("access_token") String accessToken) {}
}
