package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.invitations.client.CTraderOAuthClient;
import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.repository.BrokerIbLinkRepository;
import com.nectrix.coreapp.invitations.service.oauth.OAuthLinkStateStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 — the real cTrader OAuth linking flow's write path. Core App remains the single {@link
 * EnvelopeEncryptionService} caller (this ticket's own credential-handoff decision) —
 * apps/broker-adapters (Go) never sees plaintext tokens at rest, only already-decrypted ones handed
 * to it, per request, over the internal credentials endpoint (task #119).
 */
@Service
public class BrokerLinkingService {

  private static final String BROKER_TYPE = "CTRADER";
  // cTrader's own token response always includes expiresIn in practice, but this is a
  // defensive fallback (never observed, not something to fail linking over) — to confirm
  // the real value against a live demo account during this ticket's live-verification pass.
  private static final long DEFAULT_TOKEN_TTL_SECONDS = 3600;

  private final OAuthLinkStateStore stateStore;
  private final CTraderOAuthClient oauthClient;
  private final BrokerAdaptersInternalClient brokerAdaptersClient;
  private final BrokerAccountRepository repository;
  private final BrokerIbLinkRepository ibLinkRepository;
  private final EnvelopeEncryptionService envelopeEncryptionService;
  private final ObjectMapper objectMapper;
  private final IndividualModeCapabilityGuard capabilityGuard;

  public BrokerLinkingService(
      OAuthLinkStateStore stateStore,
      CTraderOAuthClient oauthClient,
      BrokerAdaptersInternalClient brokerAdaptersClient,
      BrokerAccountRepository repository,
      BrokerIbLinkRepository ibLinkRepository,
      EnvelopeEncryptionService envelopeEncryptionService,
      ObjectMapper objectMapper,
      IndividualModeCapabilityGuard capabilityGuard) {
    this.stateStore = stateStore;
    this.oauthClient = oauthClient;
    this.brokerAdaptersClient = brokerAdaptersClient;
    this.repository = repository;
    this.ibLinkRepository = ibLinkRepository;
    this.envelopeEncryptionService = envelopeEncryptionService;
    this.objectMapper = objectMapper;
    this.capabilityGuard = capabilityGuard;
  }

  /**
   * Starts the flow — a fresh CSRF {@code state} tied to the calling user, embedded in the URL the
   * browser is redirected to.
   */
  public String beginAuthorization(UUID userId) {
    String state = stateStore.createState(userId);
    return oauthClient.buildAuthorizeUrl(state);
  }

  /**
   * Validates+consumes {@code state}, exchanges {@code code} for real OAuth tokens, and lists the
   * accounts those tokens grant access to (via apps/broker-adapters — see
   * BrokerAdaptersInternalClient's Javadoc for why this can't be done directly). The tokens
   * themselves are cached server-side (never returned to the browser) under a new short-lived link
   * session id, which the caller must pass back to {@link #linkAccount} to actually persist one of
   * the returned accounts.
   */
  public CallbackResult handleCallback(String code, String state) {
    UUID userId = stateStore.consumeState(state).orElseThrow(InvalidOAuthStateException::new);
    CTraderOAuthClient.TokenResponse tokens = oauthClient.exchangeCode(code);
    List<BrokerAdaptersInternalClient.CtraderAccount> accounts =
        brokerAdaptersClient.listAccountsByAccessToken(tokens.accessToken());
    String expiresAt = expiresAt(tokens.expiresIn());
    String linkSessionId =
        stateStore.createLinkSession(
            userId, tokens.accessToken(), tokens.refreshToken(), expiresAt);
    return new CallbackResult(linkSessionId, accounts);
  }

  private static String expiresAt(Long expiresInSeconds) {
    long seconds = expiresInSeconds != null ? expiresInSeconds : DEFAULT_TOKEN_TTL_SECONDS;
    return Instant.now().plusSeconds(seconds).toString();
  }

  /**
   * Persists the ONE account the user picked from {@link #handleCallback}'s list. {@code
   * callerUserId} must match the link session's own user — a link session id leaked to a different
   * user must not let them link an account under their own identity using someone else's just-
   * completed OAuth grant.
   */
  public BrokerAccount linkAccount(
      UUID callerUserId,
      List<String> callerRoles,
      String linkSessionId,
      long ctidTraderAccountId,
      boolean isLive,
      String displayLabel,
      String connectionRole,
      UUID openedViaIbLinkId,
      String brokerName) {
    OAuthLinkStateStore.LinkSession session =
        stateStore.consumeLinkSession(linkSessionId).orElseThrow(InvalidLinkSessionException::new);
    if (!session.userId().equals(callerUserId)) {
      throw new InvalidLinkSessionException();
    }

    String brokerAccountLogin = Long.toString(ctidTraderAccountId);
    if (repository.existsForUser(callerUserId, BROKER_TYPE, brokerAccountLogin)) {
      throw new BrokerAccountAlreadyLinkedException();
    }

    String resolvedRole = ConnectionRoles.resolveOrDefault(connectionRole);
    if (openedViaIbLinkId != null && !ibLinkRepository.existsActiveById(openedViaIbLinkId)) {
      throw new InvalidIbLinkException();
    }
    capabilityGuard.check(callerUserId, callerRoles, resolvedRole);

    // Jackson 3's writeValueAsString is unchecked (JacksonException extends
    // RuntimeException) — no try/catch needed, matching AuthController's own convention.
    String credentialsJson =
        objectMapper.writeValueAsString(
            new StoredCredentials(
                session.accessToken(), session.refreshToken(), session.expiresAt()));
    EncryptedField encrypted = envelopeEncryptionService.encryptField(credentialsJson);

    UUID brokerAccountId =
        repository.insert(
            callerUserId,
            BROKER_TYPE,
            brokerAccountLogin,
            displayLabel,
            !isLive,
            encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
            encrypted.keyVersion(),
            resolvedRole,
            openedViaIbLinkId,
            brokerName,
            null);
    return repository.findById(brokerAccountId).orElseThrow(BrokerAccountNotFoundException::new);
  }

  public record CallbackResult(
      String linkSessionId, List<BrokerAdaptersInternalClient.CtraderAccount> accounts) {}

  /** The JSON shape encrypted as a single opaque blob in credentials_ciphertext. */
  public record StoredCredentials(String accessToken, String refreshToken, String expiresAt) {}
}
