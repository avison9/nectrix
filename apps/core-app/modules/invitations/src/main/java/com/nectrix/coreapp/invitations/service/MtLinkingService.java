package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-102 — MT5/MT4's direct-credential linking flow, the EA-bridge counterpart of {@link
 * BrokerLinkingService}'s OAuth flow. No redirect/state/callback dance: the user submits their
 * terminal login/password/server directly, this service encrypts them alongside a freshly generated
 * opaque {@code pairingToken}, and returns that token + the gateway's WebSocket URL for the user to
 * paste into their EA's own input parameters when attaching it to a chart.
 *
 * <p>The row starts {@code PENDING} and only flips to {@code CONNECTED} once a real EA session
 * presents this exact pairing token to apps/mt5-bridge-gateway (see that service's
 * internal/eabridge and internal/pairing packages) — this service never talks to the gateway
 * directly, matching TICKET-101's own credential-handoff decision (Core App is the single {@link
 * EnvelopeEncryptionService} caller; the Go side only ever sees already-decrypted values, fetched
 * per-account over the internal credentials endpoint — see {@link BrokerAccountInternalService}).
 */
@Service
public class MtLinkingService {

  private static final int PAIRING_TOKEN_BYTES = 32;

  private final BrokerAccountRepository repository;
  private final EnvelopeEncryptionService envelopeEncryptionService;
  private final ObjectMapper objectMapper;
  private final String gatewayUrl;
  private final SecureRandom secureRandom = new SecureRandom();

  public MtLinkingService(
      BrokerAccountRepository repository,
      EnvelopeEncryptionService envelopeEncryptionService,
      ObjectMapper objectMapper,
      InvitationsProperties properties) {
    this.repository = repository;
    this.envelopeEncryptionService = envelopeEncryptionService;
    this.objectMapper = objectMapper;
    this.gatewayUrl = properties.mtBridge().gatewayUrl();
  }

  public LinkResult linkMt5(UUID userId, LinkRequest request) {
    return link(userId, "MT5", request);
  }

  public LinkResult linkMt4(UUID userId, LinkRequest request) {
    return link(userId, "MT4", request);
  }

  private LinkResult link(UUID userId, String brokerType, LinkRequest request) {
    if (repository.existsForUser(userId, brokerType, request.login())) {
      throw new BrokerAccountAlreadyLinkedException();
    }

    String pairingToken = generatePairingToken();

    // Jackson 3's writeValueAsString is unchecked (JacksonException extends RuntimeException) —
    // no try/catch needed, matching BrokerLinkingService's own identical convention.
    String credentialsJson =
        objectMapper.writeValueAsString(
            new MtStoredCredentials(
                request.login(), request.password(), request.server(), pairingToken));
    EncryptedField encrypted = envelopeEncryptionService.encryptField(credentialsJson);

    UUID brokerAccountId =
        repository.insert(
            userId,
            brokerType,
            request.login(),
            request.displayLabel(),
            request.isDemo(),
            encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
            encrypted.keyVersion());

    return new LinkResult(brokerAccountId, pairingToken, gatewayUrl);
  }

  private String generatePairingToken() {
    byte[] bytes = new byte[PAIRING_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record LinkRequest(
      String login, String password, String server, boolean isDemo, String displayLabel) {}

  public record LinkResult(UUID brokerAccountId, String pairingToken, String gatewayUrl) {}

  /** The JSON shape encrypted as a single opaque blob in credentials_ciphertext. */
  public record MtStoredCredentials(
      String login, String password, String server, String pairingToken) {}
}
