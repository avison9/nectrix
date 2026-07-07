package com.nectrix.coreapp.auth.service.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * Verifies a third party's RS256-signed OIDC ID token against its published JWKS — completely
 * separate code path from {@link com.nectrix.coreapp.auth.service.JwtService}, which issues and
 * (via Spring Security's resource-server filter) verifies OUR OWN HS256 tokens. This class is
 * provider-agnostic (Google and Apple both fit this exact shape — RS256, a JWKS URL, an issuer, an
 * audience) so there's one implementation, not one per provider.
 */
public class OidcIdTokenVerifier {

  private final ConfigurableJWTProcessor<SecurityContext> processor;
  private final List<String> acceptedIssuers;

  public OidcIdTokenVerifier(List<String> acceptedIssuers, String jwksUri, String audience) {
    this.acceptedIssuers = acceptedIssuers;
    try {
      JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(URI.create(jwksUri).toURL());
      var keySelector =
          new JWSVerificationKeySelector<SecurityContext>(JWSAlgorithm.RS256, keySource);
      DefaultJWTProcessor<SecurityContext> defaultProcessor = new DefaultJWTProcessor<>();
      defaultProcessor.setJWSKeySelector(keySelector);
      // Issuer is checked manually below (Google issues both "https://accounts.google.com" and
      // "accounts.google.com" depending on token version — a single exact-match claims verifier
      // can't express "one of these two", so audience/expiry are checked here and issuer
      // separately).
      defaultProcessor.setJWTClaimsSetVerifier(
          new DefaultJWTClaimsVerifier<>(
              new JWTClaimsSet.Builder().audience(audience).build(),
              Set.of("sub", "email", "exp")));
      this.processor = defaultProcessor;
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("Invalid JWKS URI: " + jwksUri, e);
    }
  }

  public JWTClaimsSet verify(String idToken)
      throws ParseException, BadJOSEException, JOSEException {
    JWTClaimsSet claims = processor.process(idToken, null);
    if (!acceptedIssuers.contains(claims.getIssuer())) {
      throw new BadJOSEException("Unexpected issuer: " + claims.getIssuer());
    }
    return claims;
  }
}
